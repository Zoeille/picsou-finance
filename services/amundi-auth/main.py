"""Read-only Amundi Épargne Salariale authentication and positions sidecar.

Playwright drives the interactive login (reCAPTCHA-gated) and the mandatory
second factor -- either a push validation in the "Mon Épargne" app or an SMS
code -- then reads the espace épargnant's own read-only positions endpoint.
Credentials, OTP values, the bearer token and raw financial responses are
never logged.
"""

import asyncio
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
    Playwright,
    async_playwright,
)
from positions_parser import PositionsFormatError, parse_plans

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("amundi-auth")

BASE_URL = "https://epargnant.amundi-ee.com"
LOGIN_URL = f"{BASE_URL}/"
POSITIONS_PATH = "/api/individu/positionsFonds?inclurePositionVide=false&flagUrlFicheFonds=true"
AUTHENTICATED_API_PREFIX = "/api/individu/"
TOKEN_HEADER = "x-noee-authorization"

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
# The espace épargnant is a slow SPA: give the dashboard time to fire its first
# authenticated call, which is where the bearer is harvested from.
TOKEN_CAPTURE_TIMEOUT_SECONDS = 30
MFA_PROMPT_TIMEOUT_SECONDS = 20
# An app push has to be approved by a human on their phone. Amundi's own web
# client polls for about three minutes; stay under the Java adapter's timeout.
APP_VALIDATION_TIMEOUT_SECONDS = 120
SMS_VALIDATION_TIMEOUT_SECONDS = 45
POSITIONS_TIMEOUT_SECONDS = 30

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        await _cleanup_expired()


@asynccontextmanager
async def lifespan(_: FastAPI):
    sweeper = asyncio.create_task(_pending_sweeper())
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await _close_all_pending()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            log.info(
                "Amundi request completed (path=%s; duration=%.2fs)",
                request.url.path,
                time.monotonic() - started_at,
            )


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    # Absent for an app push: there is nothing to type, the user approves on
    # their phone and the page moves on by itself.
    code: str | None = Field(default=None, pattern=r"^\d{6}$")


class PositionsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    unitValue: Decimal | None = None
    valueEur: Decimal
    pnlEur: Decimal | None = None


class PlanPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    planKind: str | None = Field(default=None, max_length=30)
    employer: str | None = Field(default=None, max_length=200)
    balanceEur: Decimal
    positions: list[PositionPayload]
    snapshotComplete: Literal[True]


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    fields = {
        str(error["loc"][-1])
        for error in exc.errors()
        if error.get("loc")
    }
    detail = "INVALID_OTP" if "code" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})



class TokenCollector:
    """Harvests the `X-noee-authorization` bearer off the SPA's own traffic.

    The espace épargnant keeps the bearer in memory, so a Playwright storage
    state alone does not re-authenticate. Watching the requests the page makes
    is the one capture point that does not depend on Amundi's internal storage
    keys, which is what makes this survive their front-end refactors.
    """

    def __init__(self) -> None:
        self.token: str | None = None

    def attach(self, context: BrowserContext) -> None:
        context.on("request", self._on_request)

    def _on_request(self, request: Any) -> None:
        if self.token is not None:
            return
        try:
            if AUTHENTICATED_API_PREFIX not in request.url:
                return
            value = request.headers.get(TOKEN_HEADER)
        except Exception:  # noqa: BLE001 - a dead request must never break login
            return
        if value and value.strip():
            self.token = value.strip()

    async def wait(self, timeout_seconds: int) -> str | None:
        for _ in range(timeout_seconds * 4):
            if self.token is not None:
                return self.token
            await asyncio.sleep(0.25)
        return self.token


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    playwright: Playwright | None,
) -> None:
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
        (playwright, "stop"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, close_method)(),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("Amundi browser resource cleanup failed", exc_info=True)


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    await _close_resources(
        state.get("context"),
        state.get("browser"),
        state.get("playwright"),
    )


async def _close_pending(process_id: str) -> None:
    async with _pending_lock:
        state = _pending.pop(process_id, None)
    if state:
        await _dispose_pending_state(state)


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, state in _pending.items() if state["created_at"] < cutoff]
    for pid in expired:
        await _close_pending(pid)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _first_visible(page: Page, selectors: list[str]):
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.is_visible(timeout=800):
                return locator
        except Exception:
            continue
    return None


async def _captcha_challenge_visible(page: Page) -> bool:
    """True only for an *interactive* reCAPTCHA challenge.

    The invisible/checkbox widget is always in the DOM; it is the image-grid
    challenge popup that a headless browser cannot get past.
    """
    challenge = await _first_visible(page, [
        'iframe[title*="challenge" i]',
        'iframe[title*="défi" i]',
        'iframe[src*="recaptcha/api2/bframe"]',
    ])
    return challenge is not None


async def _otp_inputs(page: Page):
    locator = page.locator(
        'input[autocomplete="one-time-code"], '
        '.code-input input, input[name*="otp" i], input[maxlength="1"]'
    )
    try:
        if await locator.first.is_visible(timeout=800):
            return locator
    except Exception:
        return None
    return None


async def _app_validation_visible(page: Page) -> bool:
    prompt = await _first_visible(page, [
        'text=/notification/i',
        'text=/Mon Épargne/i',
        'text=/application mobile/i',
        'text=/valider.*application/i',
    ])
    return prompt is not None


def _encode_session(storage_state: dict[str, Any], token: str) -> str:
    return json.dumps(
        {"storageState": storage_state, "token": token},
        separators=(",", ":"),
    )


def _decode_session(raw: str) -> tuple[dict[str, Any], str]:
    try:
        decoded = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(decoded, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    storage_state = decoded.get("storageState")
    token = decoded.get("token")
    if not isinstance(storage_state, dict) or not isinstance(token, str) or not token:
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return storage_state, token


async def _capture_session(
    context: BrowserContext,
    collector: TokenCollector,
    timeout_seconds: int,
) -> str:
    token = await collector.wait(timeout_seconds)
    if token is None:
        return ""
    storage_state = await context.storage_state()
    return _encode_session(storage_state, token)


# Amundi's login is reCAPTCHA-gated. A default Playwright launch advertises
# HeadlessChrome and the automation flag, which reliably escalates to an image
# challenge no unattended browser can solve. These two settings are the
# difference between a silent pass and a hard block.
LAUNCH_ARGS = ["--disable-blink-features=AutomationControlled"]
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


async def _new_browser(
    pw: Playwright,
    storage_state: dict[str, Any] | None = None,
) -> tuple[Browser, BrowserContext, TokenCollector]:
    browser = await pw.chromium.launch(headless=True, args=LAUNCH_ARGS)
    context = await browser.new_context(
        locale="fr-FR",
        timezone_id="Europe/Paris",
        user_agent=USER_AGENT,
        storage_state=storage_state,
    )
    # Images are deliberately NOT blocked: reCAPTCHA scores a browser that
    # refuses them as automated. Fonts and media are dead weight either way.
    await context.route(
        "**/*",
        lambda route: route.abort()
        if route.request.resource_type in ("media", "font")
        else route.continue_(),
    )
    collector = TokenCollector()
    collector.attach(context)
    return browser, context, collector


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser, context, collector = await _new_browser(pw)
        page = await context.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        consent = await _first_visible(page, [
            '#onetrust-accept-btn-handler',
            '#didomi-notice-agree-button',
            'button:has-text("Tout accepter")',
            'button:has-text("Accepter")',
        ])
        if consent:
            await consent.click()

        # Selector lists are ordered most-specific first and deliberately
        # redundant: the espace épargnant ships front-end changes without
        # notice, and a missing field must surface as UPSTREAM_FORMAT_CHANGED
        # rather than as a wrong-credentials accusation.
        login = await _first_visible(page, [
            'input[name="login"]', '#login', 'input[name="username"]',
            'input[autocomplete="username"]',
        ])
        password = await _first_visible(page, [
            'input[name="password"]', '#password', 'input[type="password"]',
        ])
        submit = await _first_visible(page, [
            'button[type="submit"]', 'input[type="submit"]',
            'button:has-text("Se connecter")', 'button:has-text("Connexion")',
        ])
        if not login or not password or not submit:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        await login.fill(req.login)
        await password.fill(req.password)
        await submit.click()

        mfa_type: str | None = None
        for _ in range(MFA_PROMPT_TIMEOUT_SECONDS * 4):
            if collector.token is not None:
                break
            if await _captcha_challenge_visible(page):
                raise HTTPException(status_code=403, detail="CAPTCHA_BLOCKED")
            if await _otp_inputs(page) is not None:
                mfa_type = "SMS"
                break
            if await _app_validation_visible(page):
                mfa_type = "APP_PUSH"
                break
            await asyncio.sleep(0.25)

        if mfa_type is not None:
            async with _pending_lock:
                _pending[process_id] = {
                    "playwright": pw, "browser": browser, "context": context,
                    "page": page, "collector": collector, "mfaType": mfa_type,
                    "created_at": time.time(),
                }
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": mfa_type,
                "sessionState": None,
            }

        # No second factor asked for: either this device is already trusted, or
        # the credentials were rejected and we are still sitting on the form.
        session_state = await _capture_session(context, collector, TOKEN_CAPTURE_TIMEOUT_SECONDS)
        await _close_resources(context, browser, pw)
        context = browser = pw = None
        if not session_state:
            raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
        return {
            "processId": None,
            "mfaRequired": False,
            "mfaType": None,
            "sessionState": session_state,
        }
    except HTTPException:
        await _close_resources(context, browser, pw)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, pw)
        log.warning("Amundi authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, pw)
        log.exception("Unexpected Amundi authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    await _cleanup_expired()
    state = await _take_pending(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - PENDING_TTL_SECONDS:
        await _dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    page: Page = state["page"]
    collector: TokenCollector = state["collector"]
    mfa_type: str = state["mfaType"]
    try:
        if mfa_type == "SMS":
            if req.code is None:
                raise HTTPException(status_code=400, detail="INVALID_OTP")
            inputs = await _otp_inputs(page)
            if inputs is None:
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
            count = await inputs.count()
            if count >= len(req.code):
                for index, digit in enumerate(req.code):
                    await inputs.nth(index).fill(digit)
            elif count == 1:
                await inputs.first.fill(req.code)
            else:
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

            submit = await _first_visible(page, [
                'button[type="submit"]', 'button:has-text("Valider")',
                'button:has-text("Continuer")', 'button:has-text("Confirmer")',
            ])
            if submit:
                await submit.click()
            timeout = SMS_VALIDATION_TIMEOUT_SECONDS
        else:
            # Nothing to submit: the page polls Amundi's push endpoint on its
            # own while the user approves in the Mon Épargne app.
            timeout = APP_VALIDATION_TIMEOUT_SECONDS

        session_state = await _capture_session(state["context"], collector, timeout)
        if not session_state:
            if mfa_type == "SMS":
                raise HTTPException(status_code=401, detail="INVALID_OTP")
            raise HTTPException(status_code=408, detail="APP_VALIDATION_TIMEOUT")
        return {"sessionState": session_state}
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Amundi authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Amundi authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _dispose_pending_state(state)


@app.post("/positions", response_model=list[PlanPayload])
async def positions(req: PositionsRequest) -> list[PlanPayload]:
    storage_state, token = _decode_session(req.sessionState)

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser, context, _ = await _new_browser(pw, storage_state=storage_state)
        response = await context.request.get(
            f"{BASE_URL}{POSITIONS_PATH}",
            headers={
                "X-noee-authorization": token,
                "Accept": "application/json",
            },
            timeout=POSITIONS_TIMEOUT_SECONDS * 1000,
        )
        if response.status in (401, 403):
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
        if not response.ok:
            log.warning("Amundi positions request failed (status=%d)", response.status)
            if response.status == 429 or response.status >= 500:
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        try:
            payload = await response.json()
        except Exception as exc:  # noqa: BLE001 - an HTML error page lands here
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc
        return [PlanPayload.model_validate(plan) for plan in parse_plans(payload)]
    except PositionsFormatError as exc:
        log.warning("Amundi positions payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except ValidationError as exc:
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Amundi positions browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Amundi positions failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)
