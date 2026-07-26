"""Read-only Fortuneo authentication and portfolio sidecar.

Playwright drives the interactive login/2FA flow. Account/balance/
transaction data comes from Fortuneo's own GraphQL and REST APIs (see
fortuneo_parser.py), fetched by running fetch() inside the authenticated
page's own JS context via page.evaluate() -- not Playwright's out-of-band
context.request, which lacks whatever the SPA attaches to real requests
(see _page_fetch and docs/features/fortuneo.md "Verification boundaries"
for what that turned out to require: sessionStorage, which
context.storage_state() never captures). Fortuneo runs two frontends and this sidecar uses both. The modern SPA
(`/mon-espace`, OAuth/PKCE) backs the API calls above. Per-position
holdings exist only on the legacy JSP site (`/fr/prive/...`, entered via
`?ANav=1`), which authenticates by ordinary session cookie -- cookies
storage_state() does restore, which is why positions work from there even
when the SPA page itself would show a login form. Positions are read by
crossing to that site via its own /ssoacces handshake and navigating to
the account page (_open_legacy_frontend + _fetch_positions); the response
is ~200KB of server-rendered HTML. Credentials, OTP values,
cookies and raw financial responses are never logged.

STATUS: login, 2FA, account listing, balances, and transactions are verified
end-to-end against a live Fortuneo account (all four account types: PEA,
PEA-PME, CTO, Compte Courant) -- see docs/features/fortuneo.md
"Verification boundaries" for the real bugs live runs caught. Position
fetching took fifteen live-driven iterations to work out (bugs 7-21
there); the current legacy-frontend approach is implemented against
everything confirmed so far but not yet re-confirmed live end-to-end.
"""

import asyncio
import json
import logging
import os
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any
from typing import Literal
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
    Playwright,
    async_playwright,
)

from fortuneo_parser import (
    PortfolioFormatError,
    PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT,
    decimal_value,
    extract_accounts_from_equipment,
    fold_cash_pockets_into_securities_accounts,
    parse_equipment_multipart,
    parse_legacy_account_ids,
    parse_portfolio_positions,
    parse_portfolio_summary,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("fortuneo-auth")

# The data-fetching URLs below ARE confirmed, from two real HAR captures --
# see docs/features/fortuneo.md "Discovery status".
BASE_URL = "https://mabanque.fortuneo.fr"
LOGIN_URL = f"{BASE_URL}/"
API_BASE_URL = "https://api.fortuneo.fr"
EQUIPMENT_GRAPHQL_URL = f"{API_BASE_URL}/account-items-bff/graphql"
TRANSACTIONS_URL_TEMPLATE = f"{API_BASE_URL}/fto-transaction-api/v1/accounts/{{web_id}}/transactions"
# Fortuneo runs TWO frontends: the modern SPA (`/mon-espace`, OAuth/PKCE
# token auth, used for the api.fortuneo.fr calls above) and a legacy
# JSP site (`/fr/prive/...`, classic cookie-session auth) that users can
# still opt into. `ANav=1` ("ancienne navigation") is the legacy site's
# entry point. Per-position holdings only exist on the legacy side; the
# SPA's Equipment GraphQL has no position field at all.
#
# Landing here first is what puts the browser on the legacy frontend --
# every earlier attempt requested the situation URL while the browser was
# still on the SPA, and got the SPA's own shell (or its login form) back
# instead of legacy HTML. See docs/features/fortuneo.md "Verification
# boundaries" bugs 8-15.
#
# Crossing between them is an explicit handshake, not automatic: the SPA
# submits an *empty* form POST to /ssoacces, which 302-redirects to the
# legacy home page and mints the legacy session cookie on the way. Logging
# in via the SPA alone does NOT establish a legacy session -- confirmed
# live: navigating straight to the legacy home page with a
# freshly-authenticated SPA session still showed the login form.
SSO_ACCESS_PATH = "/ssoacces"
LEGACY_HOME_URL = f"{BASE_URL}/fr/prive/default.jsp?ANav=1"
LEGACY_PAGE_TIMEOUT_MS = 30_000
# `{segment}` comes from PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT; `ca` is the
# account's *legacy* id, scraped from the legacy home page's own links --
# NOT the Equipment API's id, which is a different identifier space
# entirely (see parse_legacy_account_ids). `iframe=true` asks for
# the bare embeddable fragment. Requested from the legacy frontend (after
# the /ssoacces handshake), this returns ~200KB of server-rendered HTML
# containing every holding as a `<tr name="...princip">` row.
PORTFOLIO_URL_TEMPLATE = f"{BASE_URL}/fr/prive/mes-comptes/{{segment}}/situation/?ca={{ca}}&iframe=true"

# Confirmed from the real login form's DOM (`id`/`name` attributes, which are
# far more stable than its CSS-module classnames -- those are build-hashed
# and rotate on every deploy, so they are deliberately not used here).
# Confirmed live: Fortuneo shows a TrustCommander cookie-consent overlay on
# first load that intercepts clicks on anything underneath it (including
# the login submit button) until dismissed.
COOKIE_CONSENT_ACCEPT_SELECTOR = "#popin_tc_privacy_button"
# Confirmed live: after the /ssoacces handshake the legacy site can gate
# every page behind a MiFID "Créer votre profil investisseur" interstitial
# (a `front-authz` micro-frontend rendered into a <shell-event-bridge>
# element) offering "Plus tard" / "Créer". Until "Plus tard" is taken, any
# legacy URL -- including the portfolio page -- returns the interstitial
# instead of its own content. Matched by button text, deliberately: the
# element's classnames are build-hashed (`css-1d850bn`) and rotate on
# every deploy, exactly like the login form's.
INVESTOR_PROFILE_DEFER_SELECTOR = 'button:has-text("Plus tard")'
# The gate renders with its page, so a short wait is enough. It was raised to 8s
# while diagnosing why the click never fired (bug 20) -- back then "absent" and
# "present but unclickable" were indistinguishable. That is resolved, and the
# gate is absent on most loads, so a long wait is pure dead time every sync.
INVESTOR_PROFILE_TIMEOUT_MS = 2_000
# The valuation summary block -- present on every portfolio page including
# cash-only ones, absent from the login form / interstitial / SPA shell.
PORTFOLIO_SUMMARY_SELECTOR = "#valorisation_compte"
PORTFOLIO_RENDER_TIMEOUT_MS = 10_000
# The parsed holdings must add up to the page's own "Évaluation Titres"
# total. A euro of slack absorbs rounding across many rows while still
# catching the failure that matters: a table that never rendered, which
# reads as zero holdings against a non-zero total.
PORTFOLIO_RECONCILE_TOLERANCE_EUR = Decimal("1.00")
LOGIN_FIELD_SELECTORS = ['#LOGIN', 'input[name="LOGIN"]']
PASSWORD_FIELD_SELECTORS = ['#PASSWD', 'input[name="PASSWD"]']
SUBMIT_BUTTON_SELECTORS = [
    'button[type="submit"]:has-text("Connexion")',
    'button:has-text("Connexion")',
    'button[type="submit"]',
]
# Confirmed from a real capture: the 2FA screen is six individual single-
# digit inputs (no id/name, but a stable inputmode/pattern combination) and
# a "Suivant" submit button -- same one-input-per-digit shape as Bourse
# Direct's OTP screen (see services/bourse-direct-auth/main.py `complete()`).
OTP_DIGIT_INPUT_SELECTOR = 'input[inputmode="numeric"][pattern="[0-9]*"]'
OTP_SUBMIT_BUTTON_SELECTORS = [
    'button[type="submit"]:has-text("Suivant")',
    'button:has-text("Suivant")',
    'button[type="submit"]',
]
LOGIN_FORM_TIMEOUT_MS = 15_000
LOGIN_RESULT_TIMEOUT_SECONDS = 15
OTP_RESULT_TIMEOUT_SECONDS = 15

# Confirmed verbatim from a real HAR capture (structure only, no account
# data). `@defer` fragments arrive as a chunked multipart/mixed response,
# see fortuneo_parser.parse_equipment_multipart.
EQUIPMENT_GRAPHQL_QUERY = """
query Equipment {
  equipment {
    financialPortfolio {
      lifeInsurance {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      retirementSavingsPlan {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      shareSavingsPlan {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      ordinarySecurities {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      cash {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        __typename
      }
      mortgages {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { remainingCapital { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      external {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        __typename
      }
      __typename
    }
    banking {
      current {
        label
        accounts {
          id
          label
          accountNumber
          ... @defer { overdraft __typename }
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      savings {
        label
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      external {
        label
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      __typename
    }
    __typename
  }
}
"""

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
MFA_RESULT_TIMEOUT_SECONDS = 12
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
DATA_REQUEST_TIMEOUT_MS = 30_000

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
                "Fortuneo request completed (path=%s; duration=%.2fs)",
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
    code: str = Field(pattern=r"^\d{6}$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
    symbol: str = Field(min_length=1, max_length=100)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    buyingPriceEur: Decimal | None = None
    currentPrice: Decimal | None = None
    quoteCurrency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    currentValueEur: Decimal
    pnlEur: Decimal | None = None


class TransactionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    label: str = Field(min_length=1, max_length=255)
    amount: Decimal
    category: str | None = Field(default=None, max_length=100)


class AccountPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["PEA", "COMPTE_TITRES", "CHECKING", "SAVINGS"]
    balanceEur: Decimal
    cashBalance: Decimal
    positions: list[PositionPayload]
    transactions: list[TransactionPayload] = Field(default_factory=list)
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
            log.warning("Fortuneo browser resource cleanup failed", exc_info=True)


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


async def _first_visible(page: Page, selectors: list[str], timeout_ms: int = 800):
    """Return the first selector to become visible, actually waiting/polling
    up to `timeout_ms` per selector -- NOT `Locator.is_visible(timeout=...)`,
    which despite the parameter name does a single immediate check and
    never waits for an element that doesn't exist yet (confirmed against
    the real, client-side-rendered Fortuneo login page: the naive
    `is_visible(timeout=...)` version returned False instantly regardless
    of how large `timeout_ms` was).
    """
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            await locator.wait_for(state="visible", timeout=timeout_ms)
            return locator
        except Exception:
            continue
    return None


async def _dismiss_cookie_consent(page: Page) -> None:
    """Accept the TrustCommander cookie-consent overlay if it appears. A
    no-op (short timeout, swallowed) when it doesn't -- this runs on every
    page load and must never block a session where it's already dismissed.
    """
    try:
        button = page.locator(COOKIE_CONSENT_ACCEPT_SELECTOR).first
        await button.wait_for(state="visible", timeout=4_000)
        await button.click()
    except Exception:
        pass


async def _defer_investor_profile_gate(page: Page) -> bool:
    """Take "Plus tard" on the MiFID investor-profile interstitial if it is
    blocking the legacy site (see INVESTOR_PROFILE_DEFER_SELECTOR).

    Deliberately declines rather than completes the profile: this connector
    is read-only, so it must never submit anything on the user's behalf --
    "Plus tard" only defers a prompt the user can still action themselves
    in the real UI.

    Returns whether it clicked. Never raises: the interstitial is absent on
    most loads and its absence is the normal case, not an error.

    Waits for `attached` rather than `visible`, and falls back to
    dispatching the click event directly: confirmed live, Playwright's
    actionability checks veto a normal `click()` on this React
    micro-frontend button, while `dispatch_event` goes through. A
    silently-vetoed click here means the whole portfolio fetch returns the
    interstitial instead of holdings, so the two outcomes are logged
    distinctly.
    """
    button = page.locator(INVESTOR_PROFILE_DEFER_SELECTOR).first
    try:
        await button.wait_for(state="attached", timeout=INVESTOR_PROFILE_TIMEOUT_MS)
    except PlaywrightError:
        return False  # Not gated -- the normal case, nothing to report.

    # Present. From here on, failing to dismiss it is worth shouting about:
    # it is the difference between real holdings and a useless snapshot.
    try:
        await button.click(timeout=5_000)
        return True
    except PlaywrightError:
        log.warning(
            "Fortuneo investor-profile button present but not clickable; "
            "dispatching a click event directly"
        )
    try:
        await button.dispatch_event("click")
        return True
    except PlaywrightError:
        log.warning("Fortuneo investor-profile dismissal failed outright", exc_info=True)
        return False


def _attach_client_id_capture(context: BrowserContext) -> dict[str, str | None]:
    """Capture the `client_id` field from the `/oauth-pkce/token` request the
    page's own JS makes on login success -- this is the same value later
    required as the `apikey` header on every api.fortuneo.fr data request
    (see docs/features/fortuneo.md "Discovery status"). Returns a holder
    dict the caller polls after the login navigation settles.
    """
    holder: dict[str, str | None] = {"value": None}

    def on_request(request) -> None:
        if request.method != "POST" or "/oauth-pkce/token" not in request.url:
            return
        post_data = request.post_data
        if not post_data:
            return
        try:
            client_id = parse_qs(post_data).get("client_id", [None])[0]
        except Exception:
            log.debug("Could not parse Fortuneo token request body", exc_info=True)
            return
        if client_id:
            holder["value"] = client_id

    context.on("request", on_request)
    return holder


_READ_SESSION_STORAGE_SCRIPT = """
() => {
    const out = {};
    for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i);
        out[key] = sessionStorage.getItem(key);
    }
    return out;
}
"""

_WRITE_SESSION_STORAGE_SCRIPT = """
(data) => {
    for (const [key, value] of Object.entries(data)) {
        sessionStorage.setItem(key, value);
    }
}
"""


async def _read_session_storage(page: Page) -> dict[str, str]:
    """`context.storage_state()` captures cookies and localStorage only --
    confirmed live that Fortuneo's session does NOT survive being restored
    from that alone (a fresh browser context with the exact same cookies/
    localStorage, tested immediately after login, still showed the login
    form). sessionStorage is the remaining piece Playwright's storage_state
    never captures, and SPAs commonly keep an API auth token there
    specifically because it's per-tab/session rather than persisted to
    disk -- read and carry it manually alongside storageState.
    """
    try:
        return await page.evaluate(_READ_SESSION_STORAGE_SCRIPT)
    except PlaywrightError:
        return {}


async def _restore_session_storage(page: Page, session_storage: dict[str, str]) -> None:
    if session_storage:
        await page.evaluate(_WRITE_SESSION_STORAGE_SCRIPT, session_storage)


def _session_state_json(
    storage_state: dict[str, Any],
    api_key: str,
    session_storage: dict[str, str],
) -> str:
    return json.dumps(
        {"storageState": storage_state, "apiKey": api_key, "sessionStorage": session_storage},
        separators=(",", ":"),
    )


DEBUG_ARTIFACT_DIR = "/tmp/fortuneo-debug"


async def _capture_failure_diagnostics(page: Page, label: str) -> None:
    """Best-effort diagnostic capture on an unexpected outcome -- a
    screenshot and the page's visible text (UI copy only, never the code or
    credentials) saved to DEBUG_ARTIFACT_DIR for a maintainer to inspect via
    `docker cp`. Never raises -- this must not turn a real error into a
    different one.
    """
    try:
        os.makedirs(DEBUG_ARTIFACT_DIR, exist_ok=True)
        stamp = int(time.time())
        await page.screenshot(path=f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.png", full_page=True)
        text = await page.locator("body").inner_text()
        with open(f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.txt", "w") as f:
            f.write(f"url: {page.url}\n\n{text}")
        log.info("Fortuneo diagnostic capture saved: %s_%s.{png,txt}", label, stamp)
    except Exception:
        log.warning("Fortuneo diagnostic capture failed", exc_info=True)


def _save_debug_text(label: str, text: str) -> None:
    """Best-effort raw-text diagnostic dump for a page.evaluate()-fetched
    resource. There's no live page to screenshot for these -- the browser
    never navigates, only fetch()es -- so this is the fetch-based
    counterpart to `_capture_failure_diagnostics`'s DOM/screenshot capture.
    Never raises.
    """
    try:
        os.makedirs(DEBUG_ARTIFACT_DIR, exist_ok=True)
        stamp = int(time.time())
        with open(f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.html", "w") as f:
            f.write(text)
        log.info("Fortuneo diagnostic capture saved: %s_%s.html", label, stamp)
    except Exception:
        log.warning("Fortuneo diagnostic capture failed", exc_info=True)


async def _otp_visible(page: Page) -> bool:
    try:
        return await page.locator(OTP_DIGIT_INPUT_SELECTOR).count() >= 6
    except PlaywrightError:
        return False


async def _wait_for_login_outcome(
    page: Page,
    client_id_holder: dict[str, str | None],
    timeout_seconds: int,
) -> str:
    """Poll for the two known post-submit outcomes. Returns "success",
    "otp" (the 2FA digit inputs appeared), or "timeout" (neither within the
    deadline -- most likely rejected credentials, but this sidecar has no
    confirmed selector for Fortuneo's error message to distinguish that
    from a slow page).

    Success is keyed off `client_id_holder` (populated by
    `_attach_client_id_capture` when the `/oauth-pkce/token` exchange
    fires) rather than the page URL: confirmed empirically that
    `/mon-espace` is the SPA shell's URL both *before* and *after* login
    (the login form itself renders at that same route), so URL alone can't
    distinguish the two.
    """
    for _ in range(timeout_seconds * 4):
        if client_id_holder["value"]:
            return "success"
        if await _otp_visible(page):
            return "otp"
        await asyncio.sleep(0.25)
    return "timeout"


async def _wait_for_token_exchange(
    client_id_holder: dict[str, str | None],
    timeout_seconds: int,
) -> bool:
    """Poll for the token exchange alone -- used after OTP submission.

    `_wait_for_login_outcome`'s "otp" check is wrong here: confirmed live
    (screenshot) that Fortuneo keeps the six digit inputs (now filled, with
    the submit button showing a loading spinner) on screen while it
    verifies the code -- they don't disappear or get replaced until the
    request resolves. Reusing that check made `/complete` give up after a
    single instant poll (the boxes are trivially still "visible") instead
    of actually waiting for the real outcome, misreporting a request that
    was still in flight as INVALID_OTP.
    """
    for _ in range(timeout_seconds * 4):
        if client_id_holder["value"]:
            return True
        await asyncio.sleep(0.25)
    return False


async def _capture_api_key(
    client_id_holder: dict[str, str | None],
    timeout_seconds: float = 5,
) -> str:
    deadline = time.monotonic() + timeout_seconds
    while client_id_holder["value"] is None and time.monotonic() < deadline:
        await asyncio.sleep(0.1)
    if not client_id_holder["value"]:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    return client_id_holder["value"]


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
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(locale="fr-FR")
        client_id_holder = _attach_client_id_capture(context)
        page = await context.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        # The SPA renders the login form client-side after redirecting through
        # /mon-espace -- domcontentloaded fires well before it exists, so the
        # first lookup needs a much longer timeout than the short polling
        # checks used elsewhere (confirmed empirically: a real page needed
        # several seconds past domcontentloaded, not the default 800ms).
        login_field = await _first_visible(page, LOGIN_FIELD_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        password_field = await _first_visible(page, PASSWORD_FIELD_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        if login_field is None or password_field is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await _dismiss_cookie_consent(page)
        await login_field.fill(req.login)
        await password_field.fill(req.password)

        submit = await _first_visible(page, SUBMIT_BUTTON_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        if submit is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await submit.click()

        outcome = await _wait_for_login_outcome(page, client_id_holder, LOGIN_RESULT_TIMEOUT_SECONDS)

        if outcome == "otp":
            async with _pending_lock:
                _pending[process_id] = {
                    "page": page,
                    "context": context,
                    "browser": browser,
                    "playwright": pw,
                    "client_id_holder": client_id_holder,
                    "created_at": time.time(),
                }
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "OTP",
                "sessionState": None,
            }

        if outcome == "success":
            api_key = await _capture_api_key(client_id_holder)
            session_storage = await _read_session_storage(page)
            storage_state = await context.storage_state()
            session_state = _session_state_json(storage_state, api_key, session_storage)
            await _close_resources(context, browser, pw)
            return {
                "processId": None,
                "mfaRequired": False,
                "mfaType": None,
                "sessionState": session_state,
            }

        # "timeout": neither the dashboard nor the OTP screen appeared.
        # Fortuneo has no confirmed error-message selector to distinguish
        # rejected credentials from a slow/changed page, so this is the
        # best available signal.
        raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
    except HTTPException:
        # Both success paths above return before this point, so a stored
        # pending context never reaches this handler.
        await _close_resources(context, browser, pw)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, pw)
        log.warning("Fortuneo authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, pw)
        log.exception("Unexpected Fortuneo authentication initiation failure")
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
    context: BrowserContext = state["context"]
    client_id_holder: dict[str, str | None] = state["client_id_holder"]
    try:
        inputs = page.locator(OTP_DIGIT_INPUT_SELECTOR)
        if await inputs.count() < 6:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        # `.fill()` sets the value directly and dispatches a single input
        # event -- many auto-advancing digit-box widgets key their state off
        # real keydown/keyup events instead, so `.fill()` can silently leave
        # the component's internal state empty even though the DOM value
        # looks filled. Explicitly click each box (avoids relying on
        # auto-advance targeting the right field) then send a real keypress.
        for index, digit in enumerate(req.code):
            await inputs.nth(index).click()
            await page.keyboard.press(digit)
            await asyncio.sleep(0.05)

        # Diagnostic only: confirm the digits actually landed in the DOM
        # before blaming Fortuneo for a wrong/expired code. Logs whether the
        # readback matches, never the code itself.
        entered = [await inputs.nth(i).input_value() for i in range(6)]
        log.info(
            "Fortuneo OTP digit entry verification: matches_expected=%s box_lengths=%s",
            "".join(entered) == req.code,
            [len(e) for e in entered],
        )

        submit = await _first_visible(page, OTP_SUBMIT_BUTTON_SELECTORS, 5_000)
        if submit is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        submit_disabled = await submit.is_disabled()
        log.info("Fortuneo OTP submit button disabled=%s before click", submit_disabled)
        await submit.click()

        succeeded = await _wait_for_token_exchange(client_id_holder, OTP_RESULT_TIMEOUT_SECONDS)
        if not succeeded:
            await _capture_failure_diagnostics(page, "otp_failure")
            raise HTTPException(status_code=401, detail="INVALID_OTP")

        api_key = await _capture_api_key(client_id_holder)
        # Confirmed live in /accounts's own navigation: the SPA keeps
        # client-side-redirecting for a moment after the token exchange
        # fires (e.g. login page -> dashboard). Capturing storage_state()/
        # sessionStorage immediately on client_id risks a mid-transition
        # snapshot missing a cookie or key the dashboard only sets once
        # that redirect settles. Give it the same grace `/accounts` does.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        session_storage = await _read_session_storage(page)
        storage_state = await context.storage_state()
        return {"sessionState": _session_state_json(storage_state, api_key, session_storage)}
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Fortuneo authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Fortuneo authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _dispose_pending_state(state)


def _parse_session_state(raw: str) -> tuple[dict[str, Any], str, dict[str, str]]:
    """Split the sessionState envelope into Playwright storage state, apikey,
    and sessionStorage.

    The envelope is `{"storageState": {...}, "apiKey": "...",
    "sessionStorage": {...}}` -- see `_read_session_storage` for why
    sessionStorage travels separately from storageState.
    """
    try:
        envelope = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(envelope, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    storage_state = envelope.get("storageState")
    api_key = envelope.get("apiKey")
    session_storage = envelope.get("sessionStorage") or {}
    if (
        not isinstance(storage_state, dict)
        or not isinstance(api_key, str) or not api_key
        or not isinstance(session_storage, dict)
    ):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return storage_state, api_key, session_storage


def _api_headers(api_key: str, accept: str = "application/json") -> dict[str, str]:
    return {"apikey": api_key, "accept": accept, "content-type": "application/json"}


# Confirmed live: calling account-items-bff/graphql via Playwright's
# `context.request` (a Node-side HTTP client that shares cookies but never
# runs page JS) returned 401 SESSION_EXPIRED with a freshly-authenticated,
# valid session. `api.fortuneo.fr`'s actual auth cookies (`EncFF`,
# `FORTUNEO_HTTP`) are scoped to `mabanque.fortuneo.fr` specifically and
# never reach a different subdomain -- only tracking/consent cookies are
# shared at the `.fortuneo.fr` parent-domain level. The SPA must be
# attaching whatever real credential api.fortuneo.fr needs (most likely
# read from the `in-session` localStorage entry captured alongside the
# cookies) via its own JS, either a global fetch/XHR wrapper or a service
# worker -- neither of which `context.request` goes through. Running the
# fetch from inside the page's own JS context via `page.evaluate()` instead
# picks up whatever that mechanism is automatically, without needing to
# reverse-engineer it.
_PAGE_FETCH_SCRIPT = """
async ([url, method, headers, body]) => {
    try {
        const response = await fetch(url, { method, headers, body: body ?? undefined, credentials: 'include' });
        const text = await response.text();
        return { status: response.status, text };
    } catch (e) {
        return { status: 0, text: String(e) };
    }
}
"""


async def _page_fetch(
    page: Page,
    url: str,
    method: str,
    headers: dict[str, str],
    body: str | None,
) -> dict[str, Any]:
    args = [url, method, headers, body]
    try:
        return await page.evaluate(_PAGE_FETCH_SCRIPT, args)
    except PlaywrightError as exc:
        # A stray client-side navigation can destroy the JS execution
        # context out from under an in-flight evaluate() (confirmed live,
        # same SPA behavior seen during login) -- one retry after a short
        # settle is cheap insurance against that specific race.
        if "Execution context was destroyed" not in str(exc):
            raise
        log.warning("Fortuneo page.evaluate lost its context; retrying once")
        await asyncio.sleep(1)
        return await page.evaluate(_PAGE_FETCH_SCRIPT, args)


# HAR3 (a full manual browse of the real UI) shows this page is *only* ever
# requested as `sec-fetch-dest: iframe` with `referer: .../default.jsp?ANav=1`
# -- that is the one shape observed returning the full 200KB document. Kept
# as a fallback for the plain navigation below, which is simpler but whose
# shape the captures never exercised. Deliberately does no gate handling:
# dismissing the interstitial navigates the top-level page and destroys the
# iframe mid-call (bug 21), so the caller clears the gate first.
_INJECT_IFRAME_SCRIPT = """
([url, name]) => {
    const iframe = document.createElement('iframe');
    iframe.name = name;
    iframe.style.position = 'fixed';
    iframe.style.left = '-10000px';
    iframe.style.top = '0';
    iframe.style.width = '1280px';
    iframe.style.height = '900px';
    iframe.style.border = '0';
    iframe.src = url;
    document.body.appendChild(iframe);
}
"""


async def _fetch_via_iframe(page: Page, url: str, timeout_ms: int = 20_000) -> str:
    """Load `url` in a real same-origin <iframe> on `page` and return its
    settled HTML -- reproducing the exact request shape the real UI uses.
    """
    frame_name = f"picsou-fetch-{uuid.uuid4().hex}"
    await page.evaluate(_INJECT_IFRAME_SCRIPT, [url, frame_name])

    frame = None
    deadline = time.monotonic() + (timeout_ms / 1000)
    while time.monotonic() < deadline:
        frame = next((f for f in page.frames if f.name == frame_name), None)
        if frame is not None:
            break
        await asyncio.sleep(0.1)
    if frame is None:
        log.warning("Fortuneo iframe never attached (url=%s)", url)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

    try:
        await frame.wait_for_load_state("networkidle", timeout=timeout_ms)
    except PlaywrightError:
        pass
    # Wait for the valuation summary, NOT for a holding row. A cash-only
    # securities account has no rows at all, so waiting on one always burned
    # the full timeout and then gave up -- 10s wasted per empty account, every
    # sync. The summary block is on every portfolio page, populated or not,
    # and is what the snapshot is actually validated against.
    try:
        await frame.locator(PORTFOLIO_SUMMARY_SELECTOR).first.wait_for(
            state="attached", timeout=PORTFOLIO_RENDER_TIMEOUT_MS
        )
    except PlaywrightError:
        pass
    return await frame.content()


async def _load_legacy_page(page: Page, url: str) -> str:
    """Navigate `page` (already on the legacy frontend) to `url` and return
    the settled HTML, clearing the investor-profile gate if it appears.

    Deferring that gate navigates the page itself, so the requested URL has
    to be asked for again afterwards -- hence the single retry. This is
    also why the fetch is a plain navigation instead of an <iframe>: the
    same navigation destroys an iframe and its execution context mid-call
    (confirmed live, bug 21).
    """
    for attempt in range(2):
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=LEGACY_PAGE_TIMEOUT_MS)
        except PlaywrightError as exc:
            log.warning("Fortuneo legacy page navigation failed (url=%s)", url, exc_info=True)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass

        if attempt == 0 and await _defer_investor_profile_gate(page):
            log.info("Fortuneo investor-profile interstitial deferred; re-requesting the page")
            try:
                await page.wait_for_load_state("networkidle", timeout=15_000)
            except PlaywrightError:
                pass
            continue

        try:
            return await page.content()
        except PlaywrightError as exc:
            if "navigating and changing the content" not in str(exc):
                raise
            log.warning("Fortuneo legacy page still navigating; retrying content() once")
            await asyncio.sleep(1)
            return await page.content()
    # Unreachable: the gate can only be deferred once, so the second pass
    # always falls through to the return above.
    raise AssertionError("legacy page load fell out of its retry loop")


async def _fetch_equipment(page: Page, api_key: str) -> dict[str, Any]:
    result = await _page_fetch(
        page,
        EQUIPMENT_GRAPHQL_URL,
        "POST",
        _api_headers(api_key, "multipart/mixed;deferSpec=20220824,application/json"),
        json.dumps({"query": EQUIPMENT_GRAPHQL_QUERY}),
    )
    status = result["status"]
    if status in (401, 403):
        log.warning("Fortuneo Equipment query rejected the session (status=%s)", status)
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if status == 0 or status >= 400:
        log.warning("Fortuneo Equipment query failed (status=%s)", status)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    return parse_equipment_multipart(result["text"])


async def _fetch_transactions(
    page: Page,
    api_key: str,
    web_id: str,
) -> list[dict[str, Any]]:
    result = await _page_fetch(
        page,
        TRANSACTIONS_URL_TEMPLATE.format(web_id=web_id),
        "GET",
        _api_headers(api_key),
        None,
    )
    status = result["status"]
    if status in (401, 403):
        log.warning("Fortuneo transactions request rejected the session (status=%s)", status)
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if status == 0 or status >= 400:
        log.warning("Fortuneo transactions request failed (status=%s)", status)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    try:
        raw = json.loads(result["text"])
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    if not isinstance(raw, list):
        raise HTTPException(status_code=502, detail="INVALID_DATA")

    transactions: list[dict[str, Any]] = []
    for entry in raw:
        booking_date = entry.get("bookingDate")
        amount = (entry.get("amount") or {}).get("value")
        label = (entry.get("label") or {}).get("simplifiedLabel") or (entry.get("label") or {}).get("originalLabel")
        if not booking_date or amount is None or not label:
            raise HTTPException(status_code=502, detail="INVALID_DATA")
        transactions.append({
            "date": str(booking_date)[:10],
            "label": label,
            "amount": decimal_value(amount, "transaction amount"),
            "category": ((entry.get("metadata") or {}).get("category") or {}).get("label"),
        })
    return transactions


# Submitted from a same-origin SPA document, exactly as the real frontend
# does: a real <form> POST (not fetch()) so the request is a top-level
# navigation carrying the SPA origin/referer, with an empty body -- the
# handshake authenticates purely on the cookies already in the jar.
# `form.submit()` is deferred a tick so this evaluate() can return before
# the navigation it triggers tears the execution context down -- submitting
# inline raced its own result and surfaced as "Execution context was
# destroyed" (confirmed live), the same failure mode `_page_fetch` guards
# against.
_SSO_SUBMIT_SCRIPT = f"""
() => {{
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '{SSO_ACCESS_PATH}';
    document.body.appendChild(form);
    setTimeout(() => form.submit(), 0);
}}
"""


async def _open_legacy_frontend(context: BrowserContext) -> tuple[Page, dict[str, str]]:
    """Open a page sitting on Fortuneo's legacy JSP frontend.

    Positions live only on the legacy site, and reaching it requires the
    /ssoacces handshake -- an SPA login alone leaves the legacy session
    unestablished (confirmed live: going straight to the legacy home page
    with a fresh SPA session still showed the login form). So: land on the
    SPA origin, submit the empty form POST, follow its 302 to the legacy
    home page.

    Uses its own page rather than reusing the SPA one, so this navigation
    can't disturb the JS context the Equipment/transaction fetches run in.

    Returns the page together with the legacy `ca` id of every account,
    read from the home page's own links -- the legacy site keys accounts
    by an identifier the modern Equipment API never exposes (see
    `parse_legacy_account_ids`).
    """
    page = await context.new_page()
    try:
        await page.goto(
            f"{BASE_URL}/mon-espace", wait_until="domcontentloaded", timeout=LEGACY_PAGE_TIMEOUT_MS
        )
        # The SPA client-side-redirects shortly after domcontentloaded,
        # which destroys an evaluate() raced against it -- the same settle
        # the main /accounts page does before running any JS.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        await page.evaluate(_SSO_SUBMIT_SCRIPT)
        # The POST 302s to LEGACY_HOME_URL; wait for that landing rather
        # than a fixed sleep.
        await page.wait_for_url("**/fr/prive/**", timeout=LEGACY_PAGE_TIMEOUT_MS)
    except PlaywrightError as exc:
        log.warning(
            "Fortuneo legacy frontend handshake failed (url=%s)", page.url, exc_info=True
        )
        await _capture_failure_diagnostics(page, "legacy_sso_failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    try:
        await page.wait_for_load_state("networkidle", timeout=15_000)
    except PlaywrightError:
        pass
    if await _first_visible(page, LOGIN_FIELD_SELECTORS, 800) is not None:
        await _capture_failure_diagnostics(page, "legacy_home_login_bounce")
        log.warning("Fortuneo legacy frontend bounced to the login form")
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    # Clear the investor-profile gate once, here, rather than per account
    # page: it blocks every legacy URL until deferred, so dismissing it on
    # the way in means the portfolio fetches that follow get real content.
    if await _defer_investor_profile_gate(page):
        log.info("Fortuneo investor-profile interstitial deferred")
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass

    legacy_ids = parse_legacy_account_ids(await page.content())
    if not legacy_ids:
        await _capture_failure_diagnostics(page, "legacy_home_no_account_links")
        log.warning("Fortuneo legacy home page exposed no account links")
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    log.info("Fortuneo legacy account segments discovered: %s", sorted(legacy_ids))
    return page, legacy_ids


def _is_login_page(html: str) -> bool:
    return 'name="LOGIN"' in html or 'id="LOGIN"' in html


def _portfolio_snapshot(html: str) -> dict[str, Any] | None:
    """Parse a portfolio page into `{"positions", "cashEur",
    "securitiesEur"}`, or `None` if it is not a usable snapshot.

    The page's summary table is the authority here, not the row list. It
    settles the one question the rows alone cannot answer: an account that
    genuinely holds nothing and a page whose table failed to render both
    parse to zero rows, and reporting the latter as "100% cash" is exactly
    the silent-wrong-data failure this connector exists to refuse. So the
    parsed holdings must reconcile against the page's own "Évaluation
    Titres" total. The summary also yields "Solde espèces EUR", so a PEA's
    cash is read rather than derived by subtraction.

    Parses each document once; callers may try several fetch strategies and
    the pages involved are ~200KB.
    """
    summary = parse_portfolio_summary(html)
    securities, cash = summary["securitiesEur"], summary["cashEur"]
    if securities is None or cash is None:
        return None
    positions = parse_portfolio_positions(html)
    total = sum((p["currentValueEur"] for p in positions), start=Decimal("0"))
    if abs(total - securities) > PORTFOLIO_RECONCILE_TOLERANCE_EUR:
        return None
    return {"positions": positions, "cashEur": cash, "securitiesEur": securities}


async def _fetch_positions(page: Page, segment: str, legacy_ca: str) -> dict[str, Any]:
    """Fetch a securities account's holdings and its own valuation summary.

    `page` must already be on the legacy frontend, past the /ssoacces
    handshake -- see `_open_legacy_frontend`.

    The iframe is tried first: it is the shape Fortuneo's own UI uses for
    this page, and the only one ever observed returning the full document
    (confirmed live -- a plain navigation failed for every account). The
    navigation is kept solely as a second attempt, because it is the one
    thing that *can* clear the investor-profile interstitial: dismissing
    that gate navigates the top-level page, which destroys an iframe
    mid-call. So on failure: navigate (clearing the gate if it was up),
    take that result if it is usable, otherwise return to the legacy home
    and retry the iframe now that nothing is blocking it.
    """
    url = PORTFOLIO_URL_TEMPLATE.format(segment=segment, ca=legacy_ca)

    html = await _fetch_via_iframe(page, url)
    snapshot = _portfolio_snapshot(html)

    if snapshot is None and not _is_login_page(html):
        log.info(
            "Fortuneo portfolio iframe gave no usable snapshot (segment=%s); "
            "retrying via navigation, which can clear the investor-profile gate", segment,
        )
        html = await _load_legacy_page(page, url)
        snapshot = _portfolio_snapshot(html)

        if snapshot is None and not _is_login_page(html):
            log.info("Fortuneo retrying the iframe after the navigation (segment=%s)", segment)
            try:
                await page.goto(LEGACY_HOME_URL, wait_until="domcontentloaded",
                                timeout=LEGACY_PAGE_TIMEOUT_MS)
                await page.wait_for_load_state("networkidle", timeout=15_000)
            except PlaywrightError:
                pass
            html = await _fetch_via_iframe(page, url)
            snapshot = _portfolio_snapshot(html)

    if _is_login_page(html):
        _save_debug_text(f"positions_login_bounce_{segment}", html)
        log.warning("Fortuneo portfolio page was bounced to the login form (segment=%s)", segment)
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

    if snapshot is None:
        _save_debug_text(f"positions_unusable_{segment}", html)
        if "profil investisseur" in html:
            raise PortfolioFormatError(
                "the investor-profile interstitial is still blocking the portfolio page "
                f"(segment={segment})"
            )
        if parse_portfolio_summary(html)["securitiesEur"] is None:
            raise PortfolioFormatError(
                f"the portfolio page has no valuation summary (segment={segment})"
            )
        raise PortfolioFormatError(
            "parsed holdings do not match the page's own securities total "
            f"(segment={segment})"
        )

    return snapshot


def _account_name(account: dict[str, Any]) -> str:
    type_label = account.get("typeLabel")
    account_number = account.get("accountNumber")
    if type_label and account_number:
        return f"{type_label} {account_number}"
    return type_label or account.get("label") or "Compte Fortuneo"


@app.post("/accounts", response_model=list[AccountPayload])
async def accounts(req: AccountsRequest) -> list[AccountPayload]:
    storage_state, api_key, session_storage = _parse_session_state(req.sessionState)

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(storage_state=storage_state, locale="fr-FR")
        page = await context.new_page()
        # A same-origin page context is required before evaluate()-driven
        # fetch() calls to api.fortuneo.fr can pick up whatever the SPA
        # attaches for cross-subdomain auth (see _page_fetch docstring) --
        # an unnavigated page has no origin to run that JS from.
        await page.goto(f"{BASE_URL}/", wait_until="domcontentloaded", timeout=30_000)
        # Confirmed live: the SPA client-side-redirects again shortly after
        # domcontentloaded (same behavior seen during login), which destroys
        # an in-flight evaluate() call raced against it ("Execution context
        # was destroyed"). Let that settle before running any JS.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        # Confirmed live: storageState (cookies + localStorage) alone is not
        # enough -- restoring only that showed the login form again, even
        # tested immediately after a successful login. sessionStorage is
        # the piece storage_state() never captures; restore it before
        # making any API call.
        await _restore_session_storage(page, session_storage)

        equipment = await _fetch_equipment(page, api_key)
        raw_accounts = extract_accounts_from_equipment(equipment)
        if not raw_accounts:
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        folded_accounts = fold_cash_pockets_into_securities_accounts(raw_accounts)

        payloads: list[dict[str, Any]] = []
        # Opened lazily on the first securities account -- a cash-only
        # customer never needs the legacy frontend at all.
        legacy_page: Page | None = None
        legacy_ids: dict[str, str] = {}
        for account in folded_accounts:
            if account["balanceEur"] is None:
                raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
            if not account["webId"]:
                raise HTTPException(status_code=502, detail="INVALID_DATA")

            positions: list[dict[str, Any]] = []
            if account["type"] in ("PEA", "COMPTE_TITRES"):
                segment = PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT.get(account["productType"])
                if segment is None:
                    raise HTTPException(status_code=502, detail="INVALID_DATA")
                if legacy_page is None:
                    legacy_page, legacy_ids = await _open_legacy_frontend(context)
                legacy_ca = legacy_ids.get(segment)
                if legacy_ca is None:
                    log.warning(
                        "Fortuneo legacy home page had no link for segment=%s (known: %s)",
                        segment, sorted(legacy_ids),
                    )
                    raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
                snapshot = await _fetch_positions(legacy_page, segment, legacy_ca)
                positions = snapshot["positions"]
                # The portfolio page reports its own cash ("Solde espèces
                # EUR"), from the same snapshot as the holdings -- so it is
                # both authoritative and internally consistent with them.
                # Preferred over the Equipment cash pocket (CTO) or
                # subtracting holdings from the total (PEA), which were
                # only ever approximations of this number.
                pocket_cash = account["cashBalance"]
                account["cashBalance"] = snapshot["cashEur"]
                if (
                    pocket_cash is not None
                    and abs(pocket_cash - snapshot["cashEur"]) > PORTFOLIO_RECONCILE_TOLERANCE_EUR
                ):
                    log.warning(
                        "Fortuneo cash disagrees between the Equipment pocket and the "
                        "portfolio page (segment=%s); using the portfolio page", segment,
                    )

            if account["cashBalance"] is None:
                raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")

            transactions = await _fetch_transactions(page, api_key, account["webId"])

            payloads.append({
                "externalId": account["webId"],
                "name": _account_name(account),
                "type": account["type"],
                "balanceEur": account["balanceEur"],
                "cashBalance": account["cashBalance"],
                "positions": positions,
                "transactions": transactions,
                "snapshotComplete": True,
            })

        return [AccountPayload.model_validate(payload) for payload in payloads]
    except HTTPException as exc:
        # SESSION_EXPIRED here means the restored session was rejected --
        # capture what the page actually shows (still a login form? an
        # interstitial?) since that's otherwise invisible once the browser
        # closes in `finally`.
        if exc.status_code == 401 and "page" in locals():
            await _capture_failure_diagnostics(page, "accounts_session_expired")
        raise
    except PortfolioFormatError as exc:
        log.warning("Fortuneo Equipment/transactions payload malformed: %s", exc)
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except PlaywrightError as exc:
        log.warning("Fortuneo portfolio browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Fortuneo portfolio failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)
