# Feature: Bank Sync

> Last updated: 2026-07-18 (HTTPS local-development callback default)

> **Status (1.0.0).** Enable Banking is the only enabled provider. The Powens
> adapter ships in the codebase but is **experimental and untested** —
> `@Primary` was removed from `PowensBankConnector` so Enable Banking remains
> injected as the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
> is set. Sections below referring to Powens describe the full design that
> can be re-enabled once the adapter has been validated end-to-end.

## Context

Picsou syncs bank accounts from French banks. In 1.0.0 the active provider is Enable Banking (PSD2, open banking). A second provider — Powens / Budget Insight (screen scraping) — is implemented behind `BankConnectorPort` but disabled because it has not been tested against a real Powens tenant. The scheduler runs daily auto-sync at 08:00 for all linked requisitions.

## How it works

### Provider architecture

Both providers implement the `BankConnectorPort` interface with four operations: `initiateConnection`, `exchangeCode`, `fetchBalances`, and `searchInstitutions`. The service layer (`SyncService`) never imports adapters directly -- it depends only on the port.

**Enable Banking** (`EnableBankingBankConnector`): Uses the PSD2 Bank Account Data API. Auth is JWT-based (RS256 signed with an RSA private key). Sessions are created via OAuth redirect. After the user authorizes, accounts are linked asynchronously and polled up to 3 times with 1.5-second delays (≤ 4.5 s total). If the session still has no accounts, the adapter returns an empty list rather than throwing; `SyncService` keeps the requisition in `FAILED` so the user can retry from the UI without losing the session id. The previous 24 s blocking poll caused 502 errors at the reverse proxy.

**Powens** (`PowensBankConnector`) — ⚠ experimental, disabled in 1.0.0. Uses screen scraping via the Budget Insight API. Auth is an OAuth webview that handles bank selection and credential entry. The OAuth code is exchanged for a permanent access token. Gated behind `@ConditionalOnExpression` (so it only registers when `POWENS_CLIENT_ID` is set), but `@Primary` was removed for 1.0.0, so Enable Banking remains injected even when the bean is registered.

### Requisition lifecycle

1. **CREATED** -- `SyncService.initiateConnection()` calls the port and stores a `Requisition` with `authLink`.
2. **LINKED** -- `SyncService.completeConnection()` exchanges the OAuth callback code, fetches balances, upserts at least one account, and marks the requisition as LINKED.
3. **FAILED** -- If the code exchange or balance fetch fails, or if Enable Banking returns zero accounts after polling, the requisition is marked FAILED and can be retried via `retrySync()`.

### Account type detection

`SyncService.detectType()` maps provider metadata (product name, cash account type) to the `AccountType` enum. Keywords like "pea", "lep", "livret", "titre" in the product string are matched case-insensitively. The `cashAccountType` field (e.g. "SVGS") is used as a fallback. Default is `CHECKING`.

### Key files

- `backend/src/main/java/com/picsou/adapter/EnableBankingBankConnector.java` -- PSD2 adapter (RSA JWT, async account linking)
- `backend/src/main/java/com/picsou/adapter/PowensBankConnector.java` -- Scraping adapter (experimental, OAuth webview; `@Primary` removed in 1.0.0)
- `backend/src/main/java/com/picsou/port/BankConnectorPort.java` -- Port interface with `AccountData`, `InstitutionData` records
- `backend/src/main/java/com/picsou/service/SyncService.java` -- Orchestration: initiate, complete, retry, resync, type detection
- `backend/src/main/java/com/picsou/controller/SyncController.java` -- REST endpoints under `/api/sync/`
- `backend/src/main/java/com/picsou/model/Requisition.java` -- Tracks connection lifecycle (CREATED/LINKED/FAILED)

### Flow

```
User initiates connection
        |
        v
SyncController.initiate() --> SyncService --> BankConnectorPort.initiateConnection()
        |                                         |
        |                          Enable Banking: POST /auth (RSA JWT)
        |                          Powens: build webview URL
        |
        v
User authorizes in browser --> redirect to /api/sync/complete?code=xxx
        |
        v
SyncController.complete() --> SyncService.completeConnection()
        |                         |
        |                         v
        |               BankConnectorPort.exchangeCode() --> session_id
        |                         |
        |                         v
        |               BankConnectorPort.fetchBalances(session_id)
        |                         |
        |                         v
        |               upsertAccount() with detectType()
        |                         |
        |                         v
        |               AccountService.upsertSnapshot()
        |
        v
SchedulerService.dailyBankSync() --> SyncService.resyncAll()
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Dual providers with `@Primary` | PSD2 can't access LEP/PEA/livrets; scraping covers all French account types | Single provider only |
| `@ConditionalOnExpression` for Powens | No-code activation: set env var, adapter appears; unset, it disappears | Feature toggles, profiles |
| Keyword-based type detection | Banks rarely expose a standardized type field; product name is the most reliable signal | Hardcoded institution-to-type mapping |
| Async polling for Enable Banking accounts | EB links accounts asynchronously after OAuth; polling (8x3s) handles the delay | Webhook (EB does not provide one) |
| Permanent access token for Powens | Powens tokens do not expire; stored directly as the requisition ID | Refresh token rotation (not needed) |

## Enable Banking onboarding caveats

Two pitfalls cost real users a lot of time during 1.0.0 testing — both are surfaced in the wizard now (`EBStep1Explain`, `EBStep2Credentials`):

- **PRODUCTION vs SANDBOX**: The Enable Banking developer dashboard defaults to SANDBOX, which only exposes fictitious test banks. A user who creates a SANDBOX application will reach the bank picker, see a list of unfamiliar test banks, and never find their real one. The wizard now shows a warning encart on step 1 and forces the user to tick a "my application is in PRODUCTION mode" checkbox before submitting credentials on step 3.
- **PSD2 scope is current accounts only (`CACC`)**: PSD2 standardises consent for cash accounts. PEA, Assurance Vie, Livret A, and other savings/investment products are out of scope — Enable Banking has no API for them. This is a permanent product limitation, not a Picsou bug. Users should be directed to the dedicated integrations (Trade Republic, BoursoBank sidecar, Finary) or manual entry. The wizard surfaces this on step 1, and `BankSyncTab` repeats the note above the connection list.

## Enable Banking configuration: the configured pieces

Enable Banking needs the following, stored/loaded in **two different places** — a distinction that has confused operators:

| Piece | Stored in | Set via |
| --- | --- | --- |
| Application ID | DB (`app_setting`) or `ENABLEBANKING_APPLICATION_ID` | Setup wizard / Admin → Integrations |
| Redirect URI | DB (`app_setting`) or `ENABLEBANKING_REDIRECT_URI` | Setup wizard / Admin → Integrations |
| **RSA private key** | **Filesystem** (`/data/keys/enablebanking-private.pem` in Docker, `backend/.local/keys/enablebanking-private.pem` in dev), or `ENABLEBANKING_PRIVATE_KEY_PATH` / inline `ENABLEBANKING_PRIVATE_KEY` | Setup wizard keypair step / **Admin → Integrations keypair panel** |

**Application ID == Key ID.** Per Enable Banking's [quick-start spec](https://enablebanking.com/docs/api/quick-start/) the JWT header `kid` *is* the application's ID and the private key file is named `<applicationId>.pem` — so the "Key ID" is never a distinct value. Picsou therefore collects **only the Application ID** (in both the wizard and the admin page) and derives the Key ID from it: `EnableBankingConfigProvider.keyId()` returns an explicitly-configured value if present (legacy `ENABLEBANKING_KEY_ID` env / `key-id` DB row, kept for backward compatibility), otherwise falls back to `applicationId()`. `SetupService.writeEnableBankingConfig(applicationId, redirectUri)` writes the `key-id` row in lock-step with `application-id` so a later app-id change can't be shadowed by a stale DB key-id (which, being DB-first in `resolve()`, would otherwise win).

`EnableBankingConfigProvider.isConfigured()` requires app id + key id + redirect URI + a parseable private key; `isConfiguredLenient()` / `privateKeyPresent()` are cheap, **non-parsing** checks used by status surfaces and the admin integration toggle (so a malformed key reports as "present" rather than 500-ing the page — the parse error surfaces at real use in `privateKey()`).

Because the text fields (Application ID + Redirect URI) live in Postgres while the key is a file, they can be "saved" while the key is absent — e.g. configuring via the Admin page, abandoning the wizard after the credentials step, or losing the `/data/keys` volume while the DB survives. Symptom: institution search / sync fails with a `SyncException` (HTTP 502).

**Recovery / management:**
- The **Admin → Integrations → Enable Banking** section shows a readiness banner naming every missing piece (incl. the private key) and provides a keypair panel to **generate** (idempotent — never invalidates an already-uploaded public key) or **import** a `.pem`. Endpoints: `POST /api/admin/settings/enablebanking/keypair` and `/keypair/import` (mirror the wizard's but without the setup-complete guard, so they work post-setup). `GET /api/admin/settings` returns `enableBanking.privateKeyPresent`.
- Connector errors are **field-specific**: `EnableBankingBankConnector` names the single missing piece (e.g. *"Enable Banking private key is missing on the server…"*) instead of a blanket "not configured", so the operator knows exactly what to fix.

## Gotchas / Pitfalls

- **Powens is disabled in 1.0.0**: `@Primary` was removed from `PowensBankConnector`, so even setting `POWENS_CLIENT_ID` will NOT activate Powens — Enable Banking stays injected. To re-enable after validating the adapter, restore `@Primary` on `PowensBankConnector` and set `POWENS_CLIENT_ID`.
- **Enable Banking RSA key**: The private key must be PKCS8 PEM format. The `ENABLEBANKING_PRIVATE_KEY` env var can contain literal `\n` characters -- both formats are handled in `parsePem()`. The key lives on disk, **not** in the DB — see "Enable Banking configuration" above; setting only the text fields (Application ID + Redirect URI) leaves searches failing until a key is generated/imported.
- **Local dev key path**: the `dev` Spring profile stores the generated key under `backend/.local/keys/enablebanking-private.pem` so bare-metal macOS/Linux runs do not try to create Docker's `/data/keys` directory at filesystem root.
- **Enable Banking redirect URI must be registered**: `ENABLEBANKING_REDIRECT_URI` defaults to `https://localhost:5173/sync/callback` for local development, matching Vite's HTTPS dev server. In production, set it to the public frontend callback URL (for example, `https://picsou.example.com/sync/callback` behind a TLS-terminating reverse proxy, or `http://<host>:8080/sync/callback` for a direct HTTP deployment). The exact same URL must be registered in the Enable Banking developer portal under the application's Redirect URIs. A mismatch causes a `REDIRECT_URI_NOT_ALLOWED` 400 error at auth initiation — it surfaces in the Add Account modal bank wizard.
- **ALREADY_AUTHORIZED**: If the OAuth code is reused (e.g. browser back button), `SyncService.completeConnection()` catches the error and falls back to refreshing the latest linked session instead of failing.
- **Type upgrade on resync**: If the user has not customized an account's type, `upsertAccount()` will upgrade it from CHECKING to the detected type on the next sync. Manual user changes are preserved (only CHECKING is auto-upgraded).
- **Both providers are optional**: The app starts fine without either. No `BankConnectorPort` bean is required at startup.
- **Bank logos**: `InstitutionData.logoUrl` (Enable Banking only — Powens hardcodes `null`) is captured at connection time and copied onto each `Account`. See [bank-logos.md](./bank-logos.md) for the capture/backfill flow and the account card fallback to `color`.

## Tests

- `SyncServiceTest` -- unit tests for type detection, upsert logic, retry flow
- `EnableBankingConfigProviderTest` -- DB/env resolution precedence, and `keyId()` falling back to the Application ID vs honoring an explicitly-configured value
- `EnableBankingBankConnectorTest` -- JWT build / institution search against a mocked provider
- `AdminControllerTest` -- `getSettings` reads the resolved provider; `updateEnableBanking` delegates the 2-arg writer
- `IntegrationsServiceTest` -- `isEffectivelyEnabled` = stored flag OR detected config (env/DB/session presence)
- Manual integration testing against real provider APIs

## Links

- Related ADR: [Dual bank providers](../decisions/2026-03-01-dual-bank-providers.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related: [bank-logos.md](./bank-logos.md) — logo capture/backfill and account card rendering
