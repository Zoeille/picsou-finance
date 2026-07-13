# Feature: BoursoBank Sync

> Last updated: 2026-04-26
> Status: ⏸ **Disabled in 1.0.0** — sidecar integration is not finished.

> The backend code (`BoursoAdapter`, `BoursoController`, `BoursoSession` entity,
> V23 migration) and the Python sidecar source remain in place, but the
> `bourso-auth` container is commented out in `docker-compose.yml`, the setup
> wizard catalog hides the integration, the sync page does not show the tab,
> and the admin integrations toggle is hidden. Re-enable only after the
> integration has been completed and reviewed (see `SECURITY.md`).

## Context

BoursoBank is a French online bank. Picsou syncs account balances (checking, PEA, CTO, savings), portfolio positions (PEA/CTO), and recent transactions via a `bourso-auth` Python sidecar that reverse-engineers BoursoBank's web interface.

BoursoBank uses a virtual keyboard challenge (password encoded as randomized key codes) and optional MFA (SMS/Email/App). No AWS WAF is involved, so a headless browser is not needed — plain HTTP via `httpx` is sufficient.

Reference implementation: https://github.com/azerpas/bourso-api (Rust).

## Architecture

```
BoursoTab / SyncAllModal (frontend)
    ↓ /api/bourso/*
BoursoController → BoursoSyncService → BoursoAdapter (BoursoPort)
                                             ↓ HTTP :8001
                                       bourso-auth (FastAPI / httpx)
                                             ↓ HTTPS scraping
                                         clients.boursobank.com
                                         api.boursobank.com
```

The sidecar is **stateful only during the auth flow** (in-memory processId map, 10-minute TTL). After auth completes, it returns serialized session cookies to Java. Java stores them encrypted. Subsequent sync calls pass the cookies to `POST /accounts` — the sidecar is otherwise stateless.

## Auth flow

### Without MFA
1. Frontend POSTs `{customerId, password}` to `/api/bourso/auth/initiate`
2. Sidecar: GET `/connexion/` → extract CSRF token → GET `/connexion/clavier-virtuel` → encode password via virtual keyboard → POST `/connexion/saisie-mot-de-passe`
3. Sidecar returns `sessionCookies` in the `/initiate` response
4. Java stores cookies encrypted in `bourso_session`, fires background sync
5. Frontend sees `mfaRequired: false` → done

### With MFA
1. Frontend POSTs credentials → sidecar returns `{mfaRequired: true, processId, mfaType, contact}`
2. User receives SMS/email/app notification with a code
3. Frontend POSTs `{processId, code}` to `/api/bourso/auth/complete`
4. Sidecar completes MFA: `POST /api/session/challenge/check/{otp_id}` + `POST /securisation/validation`
5. Sidecar returns `sessionCookies` → Java stores + fires background sync

### Session persistence
`sessionCookies` (serialized JSON cookie dict) is encrypted with AES-256-GCM via `CryptoEncryption` before storage. BoursoBank sessions typically last several weeks (`expiresAt = now + 30d`). If a sync call returns 401, the session is cleared and the user must re-authenticate.

### Virtual keyboard encoding
1. `GET /connexion/clavier-virtuel` returns HTML with 10 `<button data-matrix-key="XYZ">` elements (one per digit 0–9, in order) and a `data-matrix-random-challenge` token
2. Password encoding: `pad_keys[int(digit)]` for each digit, joined with `|`
3. Submitted as `form[password]` in the login POST alongside `form[matrixRandomChallenge]`

## Data fetching

### Account list
`GET /dashboard/liste-comptes?rumroute=dashboard.new_accounts&_hinclude=1` — HTML-scraped with regex to extract `data-account-id`, `data-account-label`, `data-account-balance`, `data-account-kind`.

Account type mapping:
| BoursoBank name | Picsou type |
|----------------|------------|
| "PEA" | `PEA` |
| "Compte titres" / "CTO" | `COMPTE_TITRES` |
| "LEP" | `LEP` |
| "Livret" | `SAVINGS` |
| Other / checking | `CHECKING` |

### Portfolio positions (PEA, CTO)
```
GET api.boursobank.com/…/_user__{hash}__/trading/accounts/summary/{accountId}
    ?_host=tradingboard.boursorama.com&position=ACCOUNTING&responseFormat=true
```
Returns `PositionSummary` per line: `symbol`, `label`, `quantity`, `buyingPrice`, `last`.

For each position, ISIN is fetched via:
```
GET api.boursobank.com/…/_public_/feed/instrument/quote/{symbol}?_host=tradingboard.boursorama.com
```

Java then converts ISIN → Yahoo Finance ticker via `OpenFigiIsinConverter` and stores `AccountHolding` records (same pattern as Trade Republic).

`user_hash` is extracted from `window.BRS_CONFIG = { USER_HASH: "..." }` embedded in the post-login home page.

### Transactions
```
GET /budget/exporter-mouvements
    ?movementSearch[selectedAccount]={id}&movementSearch[startDate]=DD/MM/YYYY&movementSearch[endDate]=…&movementSearch[format]=CSV
```
Returns semicolon-delimited CSV: `dateOp`, `dateVal`, `label`, `category`, `amount`, etc.

The sidecar fetches the last 90 days per account. Java replaces the 90-day window on each sync (keeping older transactions intact).

## Key files

- `services/bourso-auth/main.py` — sidecar: auth flow, accounts, positions, transactions
- `backend/src/main/java/com/picsou/adapter/BoursoAdapter.java` — calls sidecar via WebClient
- `backend/src/main/java/com/picsou/port/BoursoPort.java` — interface + data records (`InitiateResult`, `BoursoAccountData`, `BoursoPosition`, `BoursoTransaction`)
- `backend/src/main/java/com/picsou/service/BoursoSyncService.java` — auth orchestration, account/holding/transaction upsert, scheduled sync
- `backend/src/main/java/com/picsou/controller/BoursoController.java` — REST under `/api/bourso/`
- `backend/src/main/java/com/picsou/model/BoursoSession.java` — session entity (encrypted cookies, expiresAt)
- `frontend/src/pages/sync/BoursoTab.tsx` — dedicated sync page tab (auth form + sync controls)
- `frontend/src/components/sync/SyncAllModal.tsx` — inline BoursoBank auth form inside the "Sync All" modal
- `frontend/src/features/sync/api.ts` — `boursoApi` (initiateAuth, completeAuth, sync, getStatus, clearSession)
- `frontend/src/features/sync/hooks.ts` — `useBoursoSessionStatus`, `useInitiateBoursoAuth`, `useCompleteBoursoAuth`, `useSyncBourso`

## Frontend entry points

BoursoBank auth can be triggered from two places:

1. **`/sync` page → BoursoBank tab** (`BoursoTab.tsx`): Full-page form, shown always. Used for first-time setup.
2. **SyncAllModal** (`SyncAllModal.tsx`): Inline auth form appears inside the connection card when session is expired. Uses the same hooks. Detects BoursoBank presence via `accounts.some(a => a.provider === 'BoursoBank')` — the card only appears when the user already has a BoursoBank account.

## Scheduled sync

`SchedulerService.dailyBankSync()` calls `boursoSyncService.resyncIfSessionActive()` for each family member. No-op if no session or session is marked expired.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Python sidecar (httpx, no Playwright) | No AWS WAF — plain HTTP is sufficient | Headless browser (unnecessary overhead) |
| Cookies returned to Java, stored encrypted | Stateless sidecar after auth; resilient to sidecar restarts | Sidecar holds full session (lost on restart) |
| 90-day transaction window | Avoids re-importing the entire history on every sync | Full history (slow, risk of duplicates) |
| ISIN → ticker via OpenFigi | Consistent with TR pattern; enables Yahoo Finance price lookup | BoursoBank symbol as ticker (no external price lookup) |
| No-MFA path stores session in `initiateAuth` | Avoids an extra round-trip; controller doesn't need to know about cookies | Store on a separate endpoint after initiate returns |

## Gotchas / Pitfalls

- **JS cookie challenge (`__brs_mit`)**: Before serving the real login page, BoursoBank may return a page that sets `__brs_mit` via `document.cookie` in JavaScript and calls `window.location.reload()`. `httpx` doesn't execute JS, so the sidecar detects this page (regex on `document.cookie`), extracts the cookie value, sets it manually, then re-GETs `/connexion/`. Handled in `_resolve_js_cookie_challenge()`.
- **Virtual keyboard format may change**: The regex `data-matrix-key="([A-Z]+)"` matches current BoursoBank HTML. If BoursoBank changes their virtual keyboard implementation, update `_parse_vpad_html()` in the sidecar.
- **Session lasts ~30 days**: `expiresAt` is set to `now + 30d` as an estimate. BoursoBank may revoke sessions earlier (after password change, suspicious activity, etc.). If sync returns 401, the session is cleared.
- **MFA contact info**: The `contact` field (partial email/phone) is extracted from `data-confirm-contact` on the securisation page. This is informational only.
- **Positions only for PEA/CTO**: The trading summary API endpoint is only called for accounts of type `PEA` or `COMPTE_TITRES`. Savings and checking accounts return `positions: []`.
- **ISIN may be null**: If the instrument quote API call fails or the position has no ISIN, the BoursoBank symbol is used as the ticker directly.
- **`user_hash` required for positions**: If `user_hash` cannot be extracted from the home page HTML, position fetching is skipped for all accounts.
- **SyncAllModal only shows Bourso card when account exists**: The card relies on `accounts.some(a => a.provider === 'BoursoBank')`. On first setup, users must go through `BoursoTab` on the full `/sync` page; the modal shortcut is only for reconnecting an expired session.

## Tests

- `BoursoSyncServiceTest` — unit tests for auth flow (no-MFA, MFA), session handling, account upsert
- Manual integration testing against real BoursoBank accounts

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Encryption at rest](./encryption-at-rest.md)
- Related feature: [Trade Republic Sync](./trade-republic.md)
