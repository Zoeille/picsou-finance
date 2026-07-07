# Feature: Trade Republic Sync

> Last updated: 2026-07-07

## Context

Trade Republic is a German neobroker popular in France. Picsou syncs portfolio balances and holdings via TR's unofficial WebSocket API. Authentication requires a Python sidecar (`tr-auth`) because TR uses AWS WAF browser challenges that cannot be solved from plain Java HTTP. A CSV import fallback exists for when the automated sync is unavailable.

## How it works

### Authentication (delegated to Python sidecar)

The `TradeRepublicAdapter` delegates auth to the `tr-auth` Python microservice (FastAPI + Playwright, running on port 8001). The Java adapter calls three HTTP endpoints on the sidecar:

1. **`POST /initiate`** -- Sends phone number + PIN. TR dispatches a 2FA code via SMS/app notification. Returns a `processId`.
2. **`POST /complete`** -- Sends processId + verification code (4-digit code in the current Trade Republic flow). Returns `sessionToken` + `refreshToken`.
3. **`POST /refresh`** -- Sends refreshToken. Returns new sessionToken (+ possibly rotated refreshToken).

Credentials (phone/PIN) are never stored -- they are used only for the `/initiate` call and discarded.

Frontend auth state deliberately separates initiation failures from verification
failures:

- If `POST /tr/auth/initiate` fails, the phone/PIN form stays visible, the
  pending TAN and process id are cleared, and the user sees the inline error.
  The UI must not advance to the verification-code step because no valid
  `processId` exists.
- If `POST /tr/auth/complete` fails, the verification-code step stays visible,
  the typed code is cleared, and the current `processId` is kept so the user can
  retry the code without re-entering phone/PIN.

### Session persistence

`TradeRepublicSyncService.completeAuth()` stores tokens in a `TradeRepublicSession` entity and returns immediately with a `SessionStatusResponse`. The initial sync runs **in the background** on a daemon thread (`tr-sync`) using `TransactionTemplate` for programmatic transaction management — the background thread has no Spring-managed EntityManager, so `@Transactional` would not work.

Both `sessionToken` and `refreshToken` are **encrypted at rest** with AES-256-GCM via `CryptoEncryption` before storage, and decrypted on read. The refresh token has ~2-hour validity. On sync, if the session token is expired (`SESSION_EXPIRED` error), the service attempts to refresh using the stored refresh token. If refresh also fails, the session is cleared and the user must re-authenticate. See [encryption-at-rest.md](./encryption-at-rest.md) for encryption details.

### Data fetching (WebSocket, no sidecar)

The `TradeRepublicAdapter.fetchAccounts()` connects directly to `wss://api.traderepublic.com/` (protocol version 31) using `ReactorNettyWebSocketClient`. No WAF challenge is needed for the WebSocket endpoint. The adapter:

1. Sends a `connect` message with locale, platform info, and client version.
2. Subscribes to `availableCash` (cash balance) and `compactPortfolio`/`compactPortfolioByType` (list of positions with ISIN, netSize, averageBuyIn).
3. For each position, subscribes to `ticker` to get the live market price. `compactPortfolioByType` positions carry no `exchangeId`, so the adapter appends one itself: `.LSX` (Lang & Schwarz Exchange, TR's home exchange for equities/ETFs) by default, or `.TRD0` for TR-native crypto ISINs (`XF000...`), which LSX doesn't list. Using the wrong suffix makes TR reject the subscription (`FORBIDDEN`), so the position's ticker price is never received and the sync silently falls back to `averageBuyIn` — see GH issue #23.
4. Computes portfolio value as `sum(ticker.last.price * position.netSize)`.
5. Extracts secAccNo (securities account numbers) from the JWT to handle multiple sub-portfolios. The normal brokerage account is exposed under `act.acc.owner.default`; French PEA accounts are exposed under `act.acc.owner.tax_wrapper_fr`.
6. Builds `TrPosition` records from `positionsByIsin` map: each position includes ISIN, quantity (netSize), averageBuyIn, and currentPrice (from ticker, or averageBuyIn as fallback if ticker price is missing).

Returns a list of `TrAccountData` records: one securities account per TR wrapper (`TR Titres` as `COMPTE_TITRES`, `TR PEA` as `PEA`) and one cash account (`TR Cash`) for the default cash pocket. The PEA cash pocket is scoped with `availableCash(accountNumber=...)` and added to the `TR PEA` balance instead of being shown as a separate checking account.

### Holding deduplication

When persisting holdings, multiple ISINs can convert to the same Yahoo Finance ticker symbol (e.g., different listings of the same security). Before inserting into the database, `upsertAccount()` deduplicates holdings by ticker via the shared `HoldingDedup::vwapMerge` helper — quantities are summed and `averageBuyIn` is the quantity-weighted average. This prevents `DataIntegrityViolationException` on the `(account_id, ticker)` unique constraint AND keeps the cost basis mathematically correct. See [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md) for the VWAP formula and gotchas.

The WebSocket sync treats the returned position list as authoritative. Existing
holdings for a TR account are deleted and recreated on every WebSocket sync; if a
portfolio is returned with an empty position list, stale holdings are cleared. The
CSV fallback imports balances only and therefore does not replace holdings.

### CSV import fallback

`TradeRepublicSyncService.importCsv()` parses a CSV file with columns `name,type,balance`. Accounts are deduplicated via a stable external ID derived from the name (`tr_csv_` prefix + slugified name).

### Scheduled sync

`SchedulerService.dailyBankSync()` calls `TradeRepublicSyncService.resyncIfSessionActive()`, which is a no-op if no session exists or if the session has expired.

### Key files

- `adapter/TradeRepublicAdapter.java` -- WebSocket data fetching + sidecar auth delegation
- `port/TradeRepublicPort.java` -- Port interface with `TrTokens`, `TrAccountData`, `TrPosition` records
- `service/TradeRepublicSyncService.java` -- Auth flow, sync orchestration, CSV import, session management
- `controller/TradeRepublicController.java` -- REST endpoints under `/api/tr/`
- `model/TradeRepublicSession.java` -- Session entity with token storage

### Flow

```
User triggers auth
        |
        v
TRController.initiateAuth() --> TRSyncService --> TRAdapter.initiateAuth()
        |                                           |
        |                               sidecar POST /initiate (phone+PIN)
        |                                           |
        |                               <-- processId (SMS dispatched)
        v
User enters verification code
        |
        v
TRController.completeAuth() --> TRSyncService.completeAuth()
        |                             |
        |                   TRAdapter.completeAuth()
        |                             |
        |                   sidecar POST /complete (processId+tan)
        |                             |
        |                   <-- sessionToken + refreshToken
        |                             |
        |                   Save TradeRepublicSession
        |                             |
        |                   <-- SessionStatusResponse (returns immediately)
        v
  Background thread (tr-sync, TransactionTemplate):
        |
        v
  TRAdapter.fetchAccounts(sessionToken)
        |
        WebSocket: connect -> sub availableCash
                  -> sub compactPortfolio
                  -> sub ticker (per ISIN)
        |
        Build TrAccountData list
        |
        Upsert accounts + holdings

SchedulerService.dailyBankSync()
        |
        v
TRSyncService.resyncIfSessionActive()
        |
        v (if session active)
TRAdapter.fetchAccounts() --> upsert accounts
        |
        v (if SESSION_EXPIRED)
TRAdapter.refreshSession() --> retry with new token
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Python sidecar for auth | TR uses AWS WAF browser challenge; only a real browser (Playwright) can solve it | Headless Java HTTP (blocked by WAF) |
| WebSocket for data | No WAF on the WS endpoint; direct Java access works; real-time price data | REST API scraping (would also hit WAF) |
| Single session entity (`deleteAll` before save) | Single-user app; only one TR session is meaningful at a time | Multiple sessions (not needed) |
| CSV import fallback | When tr-auth is down or session expired, user can manually export and import | No fallback (bad UX) |
| Protocol version 31 hardcoded | TR protocol is undocumented and reverse-engineered; pinning avoids silent breakage | Dynamic version negotiation (not possible) |
| Input validation with `@NotBlank` on DTOs | Prevents null phone/PIN from reaching Map.of() constructor, which rejects nulls and throws NullPointerException | Try-catch in adapter (less clear, reactive) |
| User-friendly error messages on frontend | HTTP errors from sidecar are technical and nested JSON; frontend parses HTTP status + error codes to show user-friendly messages in both FR and EN | Display raw API error (confusing to users) |
| TrPosition record in port interface | Encapsulates position data (ISIN, quantity, prices) in a named record; returned within TrAccountData | Flatten positions into TrAccountData directly (less structured, harder to evolve) |
| Async background sync after auth | Auth returns immediately (~1s), sync runs on daemon thread. Frontend gets instant feedback, data appears via refetch intervals | Synchronous sync (blocks HTTP request 10-30s, bad UX) |
| `TransactionTemplate` for background sync | Background thread has no Spring proxy/EntityManager. Programmatic tx is the simplest fix. | `@Async` (self-invocation bypasses proxy), `@EnableAsync` (overhead for single use case) |
| `holdingRepository.flush()` after delete | Hibernate may defer the DELETE, causing duplicate key on subsequent INSERT with same `(account_id, ticker)` | Rely on Hibernate flush ordering (unreliable) |

## Docker / deployment

The `tr-auth` sidecar uses `python:3.12-slim` as base (not the official `mcr.microsoft.com/playwright/python` image) — see [ADR 2026-04-25](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md) for the size rationale (969MB → 547MB release archive). Only Chromium is installed; Firefox and WebKit are skipped. The container runs as a non-root user (`trauth`). Two ordering rules matter:

1. **Chromium system deps must be `apt-get install`-ed manually.** `playwright install --with-deps` would be simpler but fails on Debian bookworm because it tries to install Ubuntu-only font packages (`ttf-unifont`, `ttf-ubuntu-font-family`). The Dockerfile lists the working subset explicitly.
2. **`PLAYWRIGHT_BROWSERS_PATH` must be set BEFORE `playwright install chromium` runs**, so the browser lands in a directory owned by `trauth`:

```dockerfile
ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers
RUN pip install --no-cache-dir -r requirements.txt \
    && playwright install chromium
RUN chown -R trauth:trauth /app
USER trauth
```

Without rule 2, Playwright installs to `/root/.cache/ms-playwright/`, which the `trauth` user cannot read → every `/initiate` call throws an unhandled exception → FastAPI returns 500 with no useful error message.

Both compose files (`docker-compose.yml` at repo root and `docker/docker-compose.yml`) reference `services/tr-auth/Dockerfile`, so a fix here applies to both.

## Gotchas / Pitfalls

- **tr-auth must be running**: The Python sidecar must be accessible at `app.tr-auth.url` (default `http://tr-auth:8001`). If it is down, auth calls will timeout after 60 seconds.
- **Local Maven dev uses localhost**: `application-dev.yml` overrides `app.tr-auth.url` to `http://127.0.0.1:8001` because `tr-auth` is a Docker-internal DNS name. When running the backend with `mvn spring-boot:run`, start the sidecar separately on port 8001.
- **tr-auth 500 = Playwright crash**: A generic 500 from the sidecar almost always means the Chromium browser could not launch. Check `PLAYWRIGHT_BROWSERS_PATH` is set and `chown` covers it (see Docker section above). Run `docker logs <tr-auth-container>` to confirm.
- **Dockerfile changes need an explicit rebuild**: `docker compose up -d` does NOT rebuild existing images. After editing `services/tr-auth/Dockerfile`, run `docker compose build tr-auth && docker compose up -d tr-auth`. Symptom of a stale image: `docker compose ps` shows `tr-auth` with a SHA-only `IMAGE` column instead of a tagged name (`picsou-tr-auth` / `docker-tr-auth`), and the runtime error references a path that doesn't match the current Dockerfile (e.g. `/home/trauth/.cache/ms-playwright/` when the new Dockerfile sets `PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers`).
- **Input validation is strict**: `InitiateAuthRequest` and `CompleteAuthRequest` DTOs enforce `@NotBlank` on all fields. Empty or null values result in a 422 response before reaching the service layer. Ensure frontend sends valid non-blank values.
- **Initiation errors are not TAN prompts**: a failed `/tr/auth/initiate` call
  has no reliable `processId`, so frontend screens must keep the user on the
  phone/PIN step and clear any stale process id. Only `/tr/auth/complete` errors
  should keep the verification-code step visible for retry.
- **Frontend API field mapping**: Frontend sends `phoneNumber` and `pin` (not `phone` and `pin`). The API uses ISO field names; if frontend is updated, verify the DTO record field names match.
- **Error message parsing on frontend**: Error handling extracts specific error codes from deeply nested JSON responses (e.g., `NUMBER_INVALID`, `PIN_INVALID`, `VALIDATION_CODE_INVALID`). If the sidecar changes the error response format, frontend error messages must be updated to match. See `TradeRepublicTab.tsx` `formatAuthError()`.
- **Session expires ~2h**: The refresh token validity is approximately 2 hours. If auto-sync fails after 2h of inactivity, the user must re-authenticate manually.
- **WebSocket protocol is reverse-engineered**: The TR WebSocket API is undocumented. Raw responses are logged at INFO level. If TR changes the protocol, the adapter will break and need updating.
- **timeout-driven completion**: The WebSocket session completes when either all data is received (cash + all portfolios + all tickers) or a 30-second timeout is hit.
- **Multiple sub-portfolios / PEA**: The adapter extracts wrapper-specific `secAccNo` values from the JWT and subscribes to each one separately. `default` maps to `TR Titres` (`COMPTE_TITRES`); `tax_wrapper_fr` maps to `TR PEA` (`PEA`). This avoids merging CTO and PEA holdings into a single securities account.
- **Holding deduplication by ticker (VWAP)**: Multiple ISINs can map to the same ticker. When syncing, holdings are deduplicated in-memory before insertion to avoid unique constraint violations. Quantities are summed and `averageBuyIn` is the quantity-weighted average (VWAP) via `HoldingDedup::vwapMerge`. The earlier "keep first averageBuyIn" approach was non-deterministic (HashMap iteration order) and produced wrong gain/loss percentages. See [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md).
- **Empty WebSocket portfolio clears holdings**: If TR returns a securities account
  with zero positions, the sync deletes old holdings for that account so a full sale
  does not leave stale securities visible.
- **TrPosition currentPrice fallback**: When a ticker price is missing (ticker subscription timed out or failed), TrPosition.currentPrice is set to averageBuyIn. This allows the sync to complete without blocking on missing real-time data. Portfolio value calculation already uses this fallback logic.
- **Background sync uses `TransactionTemplate`**: The `completeAuth` background thread runs outside Spring's proxy, so `@Transactional` has no effect. It uses `TransactionTemplate` for programmatic transaction management. If you add more background sync paths, you must wrap them in `txTemplate.executeWithoutResult()` — never rely on class-level `@Transactional` from a non-Spring thread.
- **`holdingRepository.flush()` is required after delete**: `deleteByAccountId` does not guarantee immediate DB flush. Without an explicit `flush()` call before inserting new holdings, Hibernate may execute INSERT before DELETE, causing duplicate key violations on `(account_id, ticker)`.
- **SyncAllModal detects TR via accounts**: TR appears in the SyncAllModal when the user has any account with `provider === "Trade Republic"`, even without an active session. When the session is expired, clicking sync opens an inline phone + PIN + verification-code form. After successful auth, the backend sync runs in background and the frontend picks up results via existing `refetchInterval`.
- **Ticker subscription exchange suffix is guessed, not provided**: `compactPortfolioByType` gives no `exchangeId`, so `TradeRepublicAdapter` appends `.LSX` for equities/ETFs and `.TRD0` for TR-native crypto. Crypto is detected via `OpenFigiIsinConverter.isTrCryptoIsin()` (prefix `XF000`), the single shared predicate so the adapter's exchange choice and the converter's ISIN parsing can't drift. If TR introduces another on-platform product family with its own dedicated exchange, it will hit the same `FORBIDDEN` symptom as issue #23 until this default is extended.
- **Ticker completion counts distinct subscriptions, not messages**: A *successful* TR `ticker` subscription is a stream — an initial full state followed by continuous delta updates under the same `wsId`. The stream's `takeUntil` completes once every ticker subscription has answered, tracked as a set of answered `wsId`s (`answeredTickerSubs`), and only the first message per `wsId` is read (it carries the full state a sync snapshot needs). Counting raw messages instead let a fast-ticking position push the total to the expected count before slower positions had answered even once, closing the socket early and dropping their prices to the `averageBuyIn` fallback — the same symptom as issue #23. (The reference `pytr` client `unsub`s after the first message; we simply ignore later ones.)

## Tests

- `HoldingDedupTest` -- VWAP math + null handling + order independence (see [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md))
- `TradeRepublicSyncServiceTest#sync_mergesDuplicateTickersWithVwap` -- wiring test: two ISINs → same ticker → VWAP-merged `averageBuyIn` persisted
- `TradeRepublicSyncServiceTest#sync_deletesOldHoldingsWhenPortfolioReturnsEmpty` -- regression test: an authoritative empty WebSocket portfolio clears previous holdings
- `frontend/src/pages/sync/TradeRepublicTab.test.tsx` and `frontend/src/components/shared/AddAccountModal.test.tsx` -- frontend regression tests for initiation failure staying on phone/PIN and TAN completion failure staying on the verification-code step
- Manual integration testing against real TR accounts (auth flow, session refresh, CSV import)

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [tr-auth as isolated sidecar with Chromium-only image](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)
- Related feature: [Encryption at rest](./encryption-at-rest.md)
- Related feature: [Docker deployment](./docker-deployment.md)
