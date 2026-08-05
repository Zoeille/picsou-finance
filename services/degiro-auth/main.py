"""
DEGIRO Auth & Sync Sidecar
--------------------------
Handles DEGIRO authentication (username/password + TOTP) and portfolio
valuation/positions fetching, via DEGIRO's unofficial, reverse-engineered
trading API (no official public API exists — see
docs/features/degiro-sync.md and the design ADR referenced there).

CAUTION — unverified against a live account. The endpoint shapes below are
reconstructed from public reference implementations (Chavithra/degiro-connector,
bramton/degiro), not from a live test. The parts marked "NOTE: unverified"
are the most likely to need correction once tested against a real account —
see docs/features/degiro-sync.md "Known limitations".

Auth flow:
  POST /initiate  {username, password}
    → no TOTP:  {processId, totpRequired: false, sessionBlob: "..."}
    → TOTP:     {processId, totpRequired: true}

  POST /complete  {processId, code}  (only when totpRequired)
    → {sessionBlob: "..."}

  POST /portfolio  {sessionBlob: "..."}
    → {cashEur: 0.0, positions: [{isin, symbol, name, quantity, buyingPrice, currentPrice}]}

Session state is stored in memory only during the auth flow (keyed by processId).
After auth completes, an opaque {sessionId, intAccount} blob is returned to Java
for encrypted storage. Unlike the other broker sidecars, this session is
short-lived (DEGIRO times out the cookie after ~30 min of inactivity, no refresh
token) — a 401 from DEGIRO always surfaces as HTTP 401 from /portfolio, never
retried here. Picsou never stores the account's TOTP secret, so there is no
unattended re-authentication — see
docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md.
"""

import json
import logging
import time
import uuid
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from portfolio_parser import build_positions, build_product_info_map, parse_cash_eur, parse_raw_positions

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("degiro-auth")

app = FastAPI()

DEGIRO_BASE = "https://trader.degiro.nl"

# In-memory auth state: processId → {username, password, created_at}
# Cleaned up after /complete or after TTL. Credentials are held only long
# enough to retry the login with a TOTP code — never logged, never persisted.
_pending: dict[str, dict] = {}
_PENDING_TTL = 300  # 5 minutes — DEGIRO TOTP codes are valid 30s, generous margin for user entry

_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)


def _clean_pending():
    now = time.time()
    expired = [k for k, v in _pending.items() if now - v.get("created_at", 0) > _PENDING_TTL]
    for k in expired:
        _pending.pop(k, None)


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=DEGIRO_BASE,
        timeout=httpx.Timeout(30.0),
        headers={"User-Agent": _USER_AGENT, "Content-Type": "application/json"},
    )


def _session_id_from_cookies(client: httpx.AsyncClient) -> Optional[str]:
    for name, value in client.cookies.items():
        if name.upper() == "JSESSIONID":
            return value
    return None


_TOTP_NEEDED_STATUS_VALUES = {6}
_TOTP_NEEDED_STATUS_TEXT_VALUES = {"totpNeeded", "totpRequired"}


async def _login(client: httpx.AsyncClient, username: str, password: str, totp: Optional[str]) -> dict:
    """
    POST /login/secure/login (or /login/secure/login/totp when a code is supplied).
    Returns {"needsTotp": bool} or {"sessionId": "..."} on success.

    NOTE: unverified, and deliberately defensive — real logins observed so far
    (see docs/features/degiro-sync.md "Known limitations") returned shapes this
    function did not originally anticipate: an HTTP 200 + session cookie with no
    usable account behind it, and a plain HTTP 202 on the initial POST. Every
    response is logged (status, whether a cookie came back, and the JSON body —
    DEGIRO's own response, never the submitted credentials) so a live test run
    tells us exactly what to fix here next, instead of failing silently.
    """
    path = "/login/secure/login/totp" if totp else "/login/secure/login"
    body = {"username": username, "password": password, "isPassCodeReset": False, "isRedirectToMobile": False}
    if totp:
        body["oneTimePassword"] = totp

    resp = await client.post(path, json=body)
    session_id = _session_id_from_cookies(client)

    try:
        payload = resp.json()
    except ValueError:
        payload = {}

    log.info(
        "DEGIRO login response: HTTP %s, session_cookie=%s, body=%s",
        resp.status_code, bool(session_id), json.dumps(payload)[:500],
    )

    needs_totp = (
        not totp
        and (payload.get("status") in _TOTP_NEEDED_STATUS_VALUES
             or payload.get("statusText") in _TOTP_NEEDED_STATUS_TEXT_VALUES)
    )
    if needs_totp:
        return {"needsTotp": True}

    if session_id:
        # A cookie without a usable account behind it (see _fetch_int_account) is a
        # separate, already-logged failure mode — not re-guessed here.
        return {"sessionId": session_id}

    if resp.status_code in (400, 401):
        raise HTTPException(status_code=401, detail="Invalid DEGIRO credentials or TOTP code")

    raise HTTPException(
        status_code=502,
        detail=f"Unexpected response from DEGIRO login (HTTP {resp.status_code}) — see degiro-auth logs for the response body",
    )


async def _fetch_int_account(client: httpx.AsyncClient, session_id: str) -> int:
    resp = await client.get("/pa/secure/client", params={"sessionId": session_id})
    try:
        payload = resp.json()
    except ValueError:
        payload = {}
    log.info("DEGIRO /pa/secure/client response: HTTP %s, body=%s", resp.status_code, json.dumps(payload)[:500])

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail="Could not resolve DEGIRO account after login")
    int_account = payload.get("data", {}).get("intAccount")
    if int_account is None:
        # Seen live: a session cookie can come back before 2FA is actually satisfied —
        # this is likely that state, not a different account-resolution bug. The logged
        # body above is what tells us for sure.
        raise HTTPException(status_code=502, detail="DEGIRO login succeeded but no account was returned")
    return int_account


async def _fetch_portfolio(client: httpx.AsyncClient, session_id: str, int_account: int) -> dict:
    resp = await client.get(
        f"/trading/secure/v5/update/{int_account};jsessionid={session_id}",
        params={"portfolio": 0, "totalPortfolio": 0, "cashFunds": 0},
    )
    if resp.status_code == 401:
        raise HTTPException(status_code=401, detail="DEGIRO session expired")
    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail="Could not fetch DEGIRO portfolio")

    data = resp.json()
    cash_eur = parse_cash_eur(data.get("cashFunds", {}).get("value", []))
    raw_positions = parse_raw_positions(data.get("portfolio", {}).get("value", []))
    product_ids = [p["productId"] for p in raw_positions if p["productId"] is not None]

    products = await _fetch_product_info(client, session_id, int_account, product_ids) if product_ids else {}
    positions = build_positions(raw_positions, products)

    return {"cashEur": cash_eur, "positions": positions}


async def _fetch_product_info(client: httpx.AsyncClient, session_id: str, int_account: int, product_ids: list) -> dict:
    """
    POST /product_search/secure/v5/products/info — resolves productId → ISIN/name.

    NOTE: unverified — confirm the request/response envelope against a live
    account. On any failure, positions still come through (see caller) with
    the raw productId standing in for symbol/name rather than failing the
    whole sync — an incomplete label is recoverable, a missing position isn't.
    """
    try:
        resp = await client.post(
            "/product_search/secure/v5/products/info",
            params={"intAccount": int_account, "sessionId": session_id},
            json=product_ids,
        )
        if resp.status_code != 200:
            return {}
        data = resp.json().get("data", {})
        # No truncation here (unlike the login/account logs above): this is DEGIRO's
        # own market/product data, not a credential, and the previous 1000-char cap
        # cut the dump off before reaching later product ids in the batch.
        log.info("DEGIRO product info response for %s: %s", product_ids, json.dumps(data))
        products = build_product_info_map(data)
        return products
    except Exception as ex:
        log.warning("DEGIRO product info lookup failed, falling back to raw productId labels: %s", ex)
        return {}


# ─── Request/response models ───────────────────────────────────────────────

class InitiateRequest(BaseModel):
    username: str
    password: str


class CompleteRequest(BaseModel):
    processId: str
    code: str


class PortfolioRequest(BaseModel):
    sessionBlob: str


# ─── Routes ─────────────────────────────────────────────────────────────────

@app.post("/initiate")
async def initiate(req: InitiateRequest):
    _clean_pending()
    log.info("DEGIRO auth initiate for user %s***", req.username[:2])

    client = _client()
    try:
        result = await _login(client, req.username, req.password, totp=None)
    except HTTPException:
        await client.aclose()
        raise

    process_id = str(uuid.uuid4())

    if result.get("needsTotp"):
        await client.aclose()
        _pending[process_id] = {"username": req.username, "password": req.password, "created_at": time.time()}
        return {"processId": process_id, "totpRequired": True}

    try:
        int_account = await _fetch_int_account(client, result["sessionId"])
    finally:
        await client.aclose()

    blob = json.dumps({"sessionId": result["sessionId"], "intAccount": int_account})
    return {"processId": process_id, "totpRequired": False, "sessionBlob": blob}


@app.post("/complete")
async def complete(req: CompleteRequest):
    state = _pending.pop(req.processId, None)
    if not state:
        raise HTTPException(status_code=404, detail="processId not found or expired — please re-authenticate")

    log.info("DEGIRO TOTP complete for processId %s", req.processId)
    client = _client()
    try:
        result = await _login(client, state["username"], state["password"], totp=req.code)
        if result.get("needsTotp"):
            raise HTTPException(status_code=401, detail="TOTP code was rejected")
        int_account = await _fetch_int_account(client, result["sessionId"])
    finally:
        await client.aclose()

    blob = json.dumps({"sessionId": result["sessionId"], "intAccount": int_account})
    return {"sessionBlob": blob}


@app.post("/portfolio")
async def portfolio(req: PortfolioRequest):
    try:
        parsed = json.loads(req.sessionBlob)
        session_id = parsed["sessionId"]
        int_account = parsed["intAccount"]
    except (json.JSONDecodeError, KeyError):
        raise HTTPException(status_code=400, detail="Invalid sessionBlob format")

    client = _client()
    client.cookies.set("JSESSIONID", session_id, domain="trader.degiro.nl")
    try:
        return await _fetch_portfolio(client, session_id, int_account)
    finally:
        await client.aclose()


@app.get("/health")
async def health():
    return {"status": "ok"}
