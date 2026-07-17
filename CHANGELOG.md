# Changelog

All notable changes to Picsou are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **CSV transaction import for investment accounts (PEA/CTO)** and **realized
  P&L on closed positions**, computed on the fly with the average-cost method
  (#38, #43).
- **German and Spanish translations.** Supported languages are centralized in a
  locale registry (`SUPPORTED_LOCALES`); selectors and `Intl` formatting derive
  from it (#32).
- **Build version surfaced** in Settings → About and `/actuator/info`.

### Changed

- **UI controls realigned to the shadcn theme radius.** Pill-shape overrides on
  buttons, chips, and tabs were reverted to the theme tokens; Mira
  design-system pass completed with a sidebar style toggle (#46).
- **PnL no longer counts outstanding debt as an investment loss (#18).** Loans
  contribute 0 to `pnl` in every aggregation path (history points, live PnL,
  MCP `get_profit_and_loss`); `rangePnl` compares only holdings priced on both
  sides of the range; allocation percentages divide by their own side of the
  balance sheet (assets by total assets, liabilities by total liabilities).
  Net-worth totals are unchanged — loans remain liabilities. The dashboard
  chart is now titled by wealth mode instead of "Gain / Loss", its tooltip
  shows the backend's debt-neutral gain/loss, and a new Liabilities card lists
  loans separately.

### Fixed

- **Ethereum wallet balances sync again.** The hard-coded `cloudflare-eth.com`
  RPC was deprecated and started returning an HTTP 200 JSON-RPC error for
  `eth_getBalance`, which the adapter read as a 0 balance — wallets appeared to
  sync but showed nothing. Switched to `ethereum-rpc.publicnode.com`.
- **On-chain wallet adapters no longer report a false 0 on RPC failure.** Both
  the Ethereum and Solana adapters read the JSON-RPC `result` with `path(...)`,
  which turned an `error` payload (rate-limit, deprecated method, node outage)
  into a silent 0 balance rather than a sync error. They now validate the
  envelope — a present `error`, a missing `result`, or an empty response throws
  and surfaces as a `422` sync failure instead of corrupting the balance. A
  genuinely empty wallet still reads 0. On Solana this covers both the native
  SOL and the SPL-token call, and sync failures now preserve their root cause in
  the logs.
- **Wallet sync distinguishes bugs from routine RPC failures.** `WalletSyncService`
  now logs unexpected errors (NPE, etc.) at `ERROR` with a full stacktrace instead
  of a one-line `WARN`, so a real bug can't hide as a transient sync; the friendly
  `422` shown to the user is unchanged. A malformed SPL token balance or an
  unexpected token-list shape is logged and skipped (SOL and other tokens still
  sync) rather than silently dropped, and a malformed Ethereum hex balance now
  fails the sync with a clear message instead of an opaque error. Batch resync
  (`resyncAll`) reports per-wallet outcomes, so the scheduler logs which wallets
  failed and the MCP wallet-sync tool answers with the real success count.
  `WalletRpcException` now also has a dedicated `GlobalExceptionHandler` mapping
  (generic `422`) as defense-in-depth, so a bad RPC response can never surface as a
  raw `500` even on an unwrapped call path.

## [1.0.13] — 2026-07-07

### Changed

- **Setup, sync, and family safeguards improved (#29).** Hardening pass across
  the setup wizard, sync flows, and family management.

## [1.0.12] — 2026-07-07

### Fixed

- **Finary sync differentiates error types and retries transient failures
  (#27).**
- **Docker zero-config first boot works without a `.env` file** — the compose
  `env_file` entries are marked optional.

## [1.0.11] — 2026-07-05

### Added

- **Bank logos on account cards.** Enable Banking institution logos are shown
  as circular avatars, falling back to the account color when absent.

### Fixed

- **Remember Me hardening.** Persistent-session revocation is honored on
  `/auth/refresh`; sessions survive tab/browser restarts; security regressions
  in session restore closed; logout failures surface a toast instead of
  failing silently.
- **Trade Republic tickers.** Centralized XF000 crypto detection with legacy
  ticker backfill, generalized ISIN parsing (crypto exchange suffixes),
  resolved ticker `FORBIDDEN` errors and null Bitcoin ISIN names.

### Changed

- **Flyway `out-of-order` enabled** so cross-branch migrations apply cleanly.

## [1.0.10] — 2026-06-29

### Fixed

- **Enable Banking `FAILED` sessions auto-retry** on the next sync instead of
  staying stuck; Trade Republic `compactPortfolioByType` fixed.

## [1.0.9] — 2026-06-27

### Fixed

- **Finary import mapping** type dropdown includes `LOAN` and `REAL_ESTATE`.

## [1.0.8] — 2026-06-27

Security release: remediations from a 2026-06-27 security audit, the login
username-enumeration fix, and import/build fixes.

### Security

- **Login no longer leaks which usernames exist (GHSA-ww5m-pxgq-8qq6, CWE-208).**
  An unknown username now runs a decoy bcrypt comparison so its response latency
  matches a wrong-password attempt, closing the enumeration timing oracle.
- **Goals can no longer read another member's accounts (IDOR, CWE-639).**
  `GoalService.create`/`update` resolve account ids through a member-scoped
  finder instead of the unscoped `findAllById`, so a member cannot attach — and
  read the live balance of — accounts they do not own.
- **Admin password reset now invalidates the member's existing sessions
  (CWE-613/640).** `POST /api/auth/activate/{token}` is the shared sink for
  new-member activation, admin-initiated password reset
  (`FamilyService.resetPasswordToken`) and admin-recovery completion. It set the
  new password hash but — unlike self-service `change-password` — never bumped
  `tokenVersion` or revoked persistent sessions, so after an admin reset a
  (possibly compromised) member's old access/refresh JWTs and Remember-Me cookies
  stayed valid. `activate()` now bumps `tokenVersion` and calls
  `PersistentSessionService.revokeAllForUser`, mirroring `change-password`.
- **Closed a login timing oracle for pending-activation members (CWE-208).** An
  invited-but-not-activated member has a blank `password_hash`, and
  `passwordEncoder.matches(pw, "")` short-circuits without running bcrypt — making
  that path measurably faster than the unknown-user and wrong-password paths and
  letting an attacker tell a "pending-activation profile" apart from "no such
  user". `POST /api/auth/login` now runs the same dummy-hash bcrypt round when the
  stored hash is blank and fails exactly like a wrong password, so all three
  failing paths are timing-indistinguishable. Behavior for activated users is
  unchanged.
- **Static assets keep their security headers.** The nginx cache block no longer
  drops `nosniff`/CSP/`X-Frame-Options`/HSTS for `.js`/`.css` responses.

### Fixed

- **Scoped MCP tools work again — the security context now survives the
  servlet→tool thread hop.** Spring AI runs `@Tool` methods on a Reactor
  scheduler thread, but `AccessKeyAuthFilter` authenticates the `psk_` key on the
  Tomcat servlet thread by setting `SecurityContextHolder` (a `ThreadLocal`). The
  tool thread therefore saw no `Authentication`, so `ScopeEnforcementAspect`
  found no scopes and every scoped `tools/call` failed closed with "missing
  scope" — even for keys that held the scope. A `SecurityContextThreadLocalAccessor`
  is now registered with the Micrometer `ContextRegistry` and Reactor's automatic
  context propagation is enabled (`McpSecurityContextPropagationConfig`), so the
  context is captured at subscription and re-installed around tool execution. The
  accessor self-clears on every reset/close path, so a pooled scheduler thread
  never leaks one request's identity into the next. See
  [docs/lessons/thread-local-context-across-async-hop.md](docs/lessons/thread-local-context-across-async-hop.md).
- **Finary loan accounts are now imported (issue #11).** Loan/mortgage accounts
  are exposed by Finary through a dedicated `/loans` endpoint, not the portfolio
  `credits`/`credit_accounts` categories that the API sync queried — so they
  never appeared in the import preview and were never synced. The sync now also
  calls `FinaryApiClient.fetchLoans()` and adapts each entry to the common
  account shape under a synthetic `loans` category, so loans flow through the
  normal preview/mapping/execute pipeline and map to `AccountType.LOAN`. The
  outstanding amount is stored as a negative balance (a loan is a liability).
- **Frontend `Transaction` types declare the `name` field (#12).** A `name`
  field added to transactions wasn't declared on the TypeScript interfaces,
  breaking `tsc -b` (and the Docker image build) on `main`.

## [1.0.7] — 2026-06-04

Patch release: renumbers the new `transaction.name` migration so it no longer
collides with the 1.1.0 budget branch.

### Changed

- **The `transaction.name` migration moves from `V33` to `V36`.** `V33`–`V35`
  are reserved by the 1.1.0 budgets branch (`budget_foundation`,
  `budgets_envelopes`, `recurring`), so the migration introduced in 1.0.6
  collided — a dev database on the budget timeline rejected it on a Flyway
  checksum mismatch, and a future `main` ↔ `1.1.0` merge would have carried two
  `V33` files. `V33__transaction_security_name.sql` is renamed to
  `V36__transaction_security_name.sql` (the next free slot after the budget
  migrations); the SQL is unchanged. Upgrade directly to 1.0.7 from ≤ 1.0.5 so
  the column is only ever applied as `V36`.

## [1.0.6] — 2026-06-03

Patch release: manual investment entry accepts an ISIN, and positions gain a
human-readable name instead of a bare ticker.

### Added

- **Enter a position by ISIN.** The "Ticker or ISIN" field on manual investment
  entry now accepts a 12-character ISIN. `ManualTransactionService` resolves it
  to a Yahoo ticker and display name via `OpenFigiIsinConverter` at write time,
  so an ISIN entry and the equivalent ticker entry merge into a single position
  and Yahoo pricing keeps working.
- **Positions carry a first-class name.** A nullable `transaction.name` column
  holds the human label, and `HoldingComputeService` names each position from the
  most recent transaction that carries one — guarded so a nameless manual entry
  never erases a bank-synced name. The raw ticker no longer leaks into the Name
  column.

### Database migrations

- **V33** *(renumbered to `V36` in 1.0.7)* — nullable `transaction.name` column.

## [1.0.5] — 2026-06-03

Patch release: closes a session-bleed where, on a shared browser, logging in as a
2FA-protected user could drop you onto a *different* (no-MFA) member's account.

### Fixed

- **Logging in as a 2FA user no longer lands you on someone else's account.** On a
  shared browser still holding a leftover "Remember Me" cookie for a *no-MFA* account,
  a 2FA user's login correctly stopped at the TOTP step and issued no session — so the
  next request fell back on the lingering cookie and authenticated the *other* user.
  `POST /api/auth/login` now severs any pre-existing session cookies the moment a
  password is verified while the second factor is still pending, and drops a leftover
  persistent ("Remember Me") cookie belonging to a different user when completing a
  login without Remember Me. A trusted device's own cookie is preserved.
- **The login path now resets per-user client state.** The cache and impersonation
  reset that already ran on logout (1.0.4) now also runs the instant a login
  establishes a session (non-MFA login and MFA verification), centralised in a single
  `resetClientState` helper. This stops a freshly logged-in user from briefly seeing
  the previous user's cached balance/history on a shared browser.

## [1.0.4] — 2026-06-03

Patch release: fixes two issues hitting non-admin family members — a spurious 403
on first login and seeing another member's financial data on a shared browser.

### Fixed

- **A non-admin member no longer gets a 403 / `/error/403` redirect on login.** The
  sidebar called the admin-only `GET /api/family/members` for every user; the global
  403 interceptor then bounced the whole app to the error page. The call is now gated
  on `isAdmin`, so non-admins never hit the admin-only endpoint.
- **A member no longer sees another member's balance and net-worth history.** On a
  shared browser the persisted impersonation target (`activeMemberId`, in localStorage)
  and the user-agnostic TanStack Query cache survived logout, so the next person's first
  login showed the previous user's data. Login and logout now reset the profile store
  and clear the query cache. As defense-in-depth, the client only sends `?memberId` for
  admins, and `HistoryService` enforces account ownership unconditionally (a `null`
  member id is now rejected instead of bypassing the check).

## [1.0.3] — 2026-06-03

Patch release: clearer Enable Banking setup, an honest admin configuration view,
and integration toggles that reflect what's actually configured.

### Changed

- **Enable Banking now asks for a single "Application ID" instead of two near-identical
  fields.** Per Enable Banking's spec the JWT `kid` *is* the application's ID and the key
  file is named `<applicationId>.pem`, so the "Key ID" was never a distinct value — users
  retyped the same UUID twice, an easy way to mis-key one. Both the setup wizard and
  Admin → Integrations now collect only the Application ID and derive the Key ID from it,
  with a hint explaining they're the same value. Existing installs that set
  `ENABLEBANKING_KEY_ID` (env) or have a `key-id` row keep working — `keyId()` honors an
  explicitly-configured value and only falls back to the Application ID otherwise.

### Fixed

- **Admin → Integrations no longer shows Enable Banking as "not configured" when it works.**
  The settings view read raw DB rows while the connector resolves credentials DB-then-env,
  so an install configured via `.env` saw empty fields and a false warning banner. The
  admin view now reads the same resolved provider the connector uses.
- **Integration toggles reflect actual configuration, not just a stored boolean.** An
  integration configured via `.env`/Docker (Enable Banking, Trade Republic, Finary) now
  reports as enabled even with no DB intent-flag set — the toggle is `stored-flag OR
  detected-config` rather than trusting the flag alone.

## [1.0.2] — 2026-06-02

Patch release: fixes a **403** that blocked login and the setup wizard's **Origins**
step when Picsou is served over HTTPS behind a reverse proxy.

### Fixed

- **403 "Request failed with status code 403" over HTTPS behind a reverse proxy.**
  Picsou serves the SPA and the API from the same origin, so login/setup traffic is
  same-origin and should never hit CORS. But Spring's `CorsUtils.isCorsRequest()`
  compares the `Origin`'s scheme/host/port against the request's, and with no
  forwarded-headers handling the TLS-terminated backend saw `http` while the browser
  sent `https` — the scheme mismatch made same-origin requests look cross-origin and
  the fail-closed allow-list rejected them with `403`. The backend now trusts proxy
  headers (`server.forward-headers-strategy: framework`) and the bundled nginx
  preserves the upstream `X-Forwarded-Proto`/`Host`/`Port` instead of overwriting them
  with its own plain-HTTP `$scheme`. Same-origin HTTPS traffic is now correctly
  recognized and the wizard works without pre-seeding `ALLOWED_ORIGINS`.

  > Behind a reverse proxy, ensure it forwards `X-Forwarded-Proto: https`
  > (Caddy, Traefik, Nginx Proxy Manager and Cloudflare Tunnel do by default).

## [1.0.1] — 2026-06-02

Patch release: a crash-recovery fix for invalid account currencies, a dependency
security bump, and Docker image-tagging refinements.

### Fixed

- **Invalid currency codes no longer crash the whole app.** A manual account saved
  with a non-ISO-4217 code (e.g. `AMAT`) made `Intl.NumberFormat` throw a `RangeError`
  in `formatCurrency`; the throw bubbled to the root error boundary and blanked the
  entire UI, leaving the offending account impossible to reach, edit, or delete.
  `formatCurrency` now degrades to `"<amount> <code>"` instead of throwing (which also
  rescues any already-persisted bad account), the account form uses a curated currency
  dropdown with locale-aware labels, and the backend rejects unknown codes with a `400`
  via a new `@ValidCurrency` constraint (`0703274`). Closes #9.

### Security

- **Dependency advisories patched.** `bcprov-jdk18on` 1.78.1 → 1.84 and `poi-ooxml`
  5.3.0 → 5.4.0 (`5a7e776`).

### Changed

- **Docker image tagging.** `main` builds are now published as `latest` and version
  tags drop the `v` prefix (`5f887ce`); deployment docs run from the published GHCR
  images instead of building locally (`50dc0e1`).

## [1.0.0] — 2026-06-02

First stable release. Builds on the MVP (commit `37920d1`) with multi-member
families, 2FA + persistent sessions, a zero-config setup wizard, bank/broker/crypto
integrations, loan amortization, GDPR data export, savings-goal trajectories,
per-holding security insight, and hundreds of refinements across security,
internationalization, and mobile responsiveness.

### Added

#### Multi-account family
- **Family members & roles.** New `FamilyMember` entity, `UserRole` enum, sharing
  entities, and per-row `member_id` foreign keys across all financial tables
  (`d95b7b7`, `d7abdf2`).
- **Member-scoped services.** All services, controllers, and repositories scope
  queries by `memberId`, with admin override for impersonation (`68a97ba`,
  `2dbbeb6`).
- **`UserContext` helper.** Single source of truth for "who is the current user
  and which member are they viewing?" (`050c49d`, `c548b7f`).
- **Family API & UI.** `FamilyController`, `FamilyViewController`, family stores,
  sidebar, and pages for creating members, switching profiles, and viewing
  shared resources (`a5308e8`, `a3ee1c0`).
- **Username change with token rotation.** Settings → Profile lets a user rename
  themselves; access/refresh JWTs are re-issued so the session doesn't drop
  (`7f8c988`, `3454307`).
- **Admin members management.** Admins can invite, activate, reset password, and
  reset 2FA for other members (`6a3c978`, `36ef439`).
- **Create members from the Admin page.** Admin → Members can now create new
  users directly, deriving the login from the display name (`e305096`).
- **Delete members with an active account.** Admins can remove a member even
  when that member still owns an account, without hitting a
  `TransientObjectException` (`a55dfb2`, `682a6f3`).

#### Two-factor authentication & persistent sessions
- **TOTP 2FA.** `UserMfa`, recovery codes, anti-replay window, full enrolment
  wizard, and verification challenge page (`98243c5`, `ccddd88`, `6dd476a`,
  `a95d752`, `5f60ce5`, `c59a439`).
- **Remember Me / persistent sessions.** Rotating tokens with theft detection,
  silent re-login filter, and admin force-disable (`6c1ae82`, `a481128`,
  `5f724e6`, `7afd7d0`).
- **MFA-aware controllers.** `MfaController` (step-up reauth) and
  `SessionController` (list/revoke active sessions) (`0df1f68`, `7afd7d0`,
  `db40cf6`, `07a4fff`).
- **Stateless JWT invalidation on password change.** New `token_version` claim
  bumped on password change; persistent sessions revoked, refresh+access cookies
  re-issued (`056a900`).
- **Admin password recovery.** `ADMIN_RECOVERY_ENABLED=true` triggers an
  `ApplicationRunner` at boot that mints a 1-hour activation token for the admin
  account, bumps `tokenVersion` to invalidate active sessions, and prints the URL
  to the logs. The login page points the operator to the console reset link while
  recovery is active. Documented in `docs/features/admin-recovery.md` (`9297246`,
  `cd3d88e`).

#### Setup wizard (zero-config first launch)
- **Guided first-boot flow.** `SetupController`, `SetupService`, `SetupFilter`,
  audit log, and `AppSetting` key/value store let admins complete admin account,
  security (CORS, secure cookies, encryption key), and integrations setup with no
  env-var editing (`627533a`).
- **Per-integration mini-wizards.** Enable Banking (5-step keypair flow),
  BoursoBank, Trade Republic, Finary, and crypto-exchange setup pages (`627533a`).
- **CORS reload from environment.** `POST /api/admin/settings/cors/reload-from-env`
  plus a "Reload from environment" button in the admin Security panel — an escape
  hatch when the operator changes the public URL after the wizard ran (`9297246`).

#### Security insight (per holding)
- **Asset type + ETF composition.** `SecurityInsightService` classifies a holding
  (stock / ETF / …) and, for ETFs, surfaces top companies, country, and sector
  breakdowns inside the holding detail modal (`befb13d`).
- **Boursorama composition provider.** Composition is resolved and parsed from
  Boursorama (302-redirect resolution), the single source after the issuer-holdings
  approach was superseded; the port returns an aggregated `EtfComposition` and the
  per-issuer adapters were dropped (`68caf43`, `1f41932`, `dfb1407`, `ca49378`).
- **Stable i18n keys for sectors/countries.** Boursorama FR labels map to stable
  i18n keys with a graceful fallback to the raw label when a key is missing
  (`8bdfc4f`, `bdecde3`).
- **Block / line composition views.** A small per-section toggle switches each
  composition between labeled blocks and a slim colour bar + wrapping legend; the
  line view keeps the breakdown readable on phones (`a66f043`, `0225a25`).

#### Integrations
- **BoursoBank** *(disabled in 1.0.0)*. Python sidecar (Selenium-based),
  `BoursoAdapter`, `BoursoController`, sessions table, V23 migration shipped but
  the sidecar and all UI entry points are gated off until the integration is
  finished (`b953e55`).
- **Trade Republic compact portfolio.** `compactPortfolio` with `secAccNo` for
  portfolio sync; deduplication when multiple ISINs map to the same ticker
  (`91972b6`, `1b91abf`).
- **Finary.** Full Finary API import + auto-sync, holdings preservation across
  syncs, manual-transaction protection, daily scheduler, proactive TOTP detection
  in the Add Account modal (`6c9777e`, `93c7ee0`, `0328533`, `1faa1d8`, `0f4bf07`,
  `fbeb155`).
- **Powens** *(experimental, disabled in 1.0.0)*. `PowensBankConnector` ships in
  the codebase but is untested end-to-end; `@Primary` was removed so Enable
  Banking remains the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
  is set (`6c9777e`).
- **Crypto exchanges.** Binance integration via `CryptoExchangePort` (`b70b2aa`,
  `aab51c4`).
- **On-chain wallets.** Bitcoin (xpub, zpub, output descriptors), Ethereum, and
  Solana via dedicated adapters (`b70b2aa`, `2eda584`, `4b51f32`).
- **Solana SPL token support.** `SolanaWalletAdapter` also calls
  `getTokenAccountsByOwner` and surfaces known stablecoins (USDC, EURC, USDT)
  alongside the native SOL balance. `WalletPort.fetchBalance` → `fetchBalances`
  returning `List<WalletBalance>`; Ethereum and Bitcoin adapters adapted to the
  list signature (`9297246`).
- **OpenFIGI ISIN → ticker resolver** for Yahoo Finance prices (`0bf43c6`).

#### Loans
- **On-the-fly amortization.** `LoanAmortizationService` derives the schedule from
  loan inputs without storing a row per month; the UI renders a progress card,
  monthly breakdown, cost summary, and amortization chart (`52aa8e5`).
- **Extra loan fields.** V27 migration adds APR, fees, and supporting columns on
  `Debt` (`52aa8e5`).

#### Manual transactions
- **CRUD endpoints.** `POST` / `DELETE /api/transactions/...`, with the manual
  flag preserved across re-syncs (`969ba10`, `93c7ee0`).
- **Investment transactions.** Holdings auto-fill; BUY/SELL drive position
  derivation via `HoldingComputeService` (`a12321a`, `4f534ac`, `9b144d8`,
  `210d758`).
- **AddTransactionModal** with hooks, error handling, and a TransactionsList
  delete action (`429eca0`, `b5b1d41`).

#### Goals
- **Donut year cards.** Year grid view rendered as donut cards using `oklch` CSS
  vars (`f30d77b`, `33bbcf5`, `a3f9168`).
- **Ideal target trajectory.** The goal detail chart draws an ideal-pace line
  anchored at the baseline, plus an at-current-pace projection, and crops to the
  goal's `createdAt` instead of the full history window; the X axis is now a true
  time scale so uneven date spans render cleanly (`a865c37`, `1fb6ff8`, `25ef6bd`,
  `1f4a9d4`).
- **`isOnTrack` from cumulative objective.** On-track status is derived from the
  cumulative past-month objective vs. effective contributions, giving the benefit
  of the doubt when there is no past data (`3f4ec38`).
- **Flexible contribution editing.** Comma- or point-decimal inputs, side-panel
  calendar editing, and history backfill for manual contributions (`55737ad`).

#### GDPR data export
- **Self-service export** of profile, accounts, holdings, transactions, goals,
  debts, wallets, sharing settings, bank connections, and balance snapshots in
  JSON + CSV, gated by re-authentication and rate-limited (`d0933ac`, `3f58502`,
  `9287d47`, `66a274a`, `22dd58b`).

#### History, debts, real estate
- **History/PnL endpoints**, debts, real estate metadata, error pages, mobile
  nav, sync modal (`dad9bea`).

#### Holdings UX
- **Edit & detail modals**, empty-chart state with intraday support (`de40df0`).

#### Other
- **Mandatory encryption** of secrets at rest (`6c1ae82`, `6c9777e`).
- **Theme persistence** across sessions (`6c9777e`).
- **All-in-one Docker image** with supervisord, dev hot-reload Dockerfiles,
  slimmer `tr-auth` sidecar (`cd651e6`).
- **GHCR publishing.** CI publishes per-branch images on every push and ships the
  `tr-auth` sidecar image alongside the main app (`24dfeb7`, `751160e`).

### Changed

- **Frontend rewrite** with shadcn/ui and a feature-based architecture (`33a384c`).
- **Branding** — the frontend package is now `picsou`, with a black-logo favicon
  (`4c4ca66`).
- **Icon migration** from hugeicons to `lucide-react` (`f3bb858`).
- **Locale-aware formatting** helpers via `getLocale()` (`3b9b0f4`); all remaining
  date displays routed through locale-aware formatters (`37b2cab`); date inputs
  honor the configured date-format setting via a hybrid native/desktop
  `DateInput` (`d66ff93`).
- **Centralized error formatting.** Backend messages are English and surfaced
  through a single friendly frontend formatter (`formatApiError`, which accepts an
  i18next `TFunction`) (`b682a0e`, `c04b4b0`).
- **Add Account modal** unified with embedded sync wizards (`7a96584`).
- **Layout, sidebar, dashboard, account detail** redesign + updated demo data
  (`001551f`).
- **Mobile responsiveness** across the whole app (`5017206`).
- **License** changed from MIT to Apache 2.0 + Commons Clause (`e9185c9`,
  `25825e5`).
- **Enable Banking setup wizard** — the redirect URI is shown before the
  credentials step so users can whitelist it before generating their Application
  ID / Key ID; the test step warns that EB only activates the application after a
  real bank account is connected (otherwise the test reports a misleading "invalid
  key"); SANDBOX vs PRODUCTION warning banner and a mandatory acknowledgement
  checkbox; PSD2 scope note (current accounts only) in step 1 and `BankSyncTab`;
  sign-in link updated to `https://enablebanking.com/sign-in/` (`9297246`).
- **Trade Republic Add Account** — the PIN field is masked (bullets) like a
  password input.
- **Setup wizard intro** — `HelloGreeting` cadence cut from ~19 s to ~7.8 s, with
  an explicit Skip button and a 5 s watchdog so a stalled font/i18n load can never
  block the wizard (`9297246`).

### Fixed

- **CORS with credentials.** Forbid wildcard origins; default to empty (no
  cross-origin); strip `*` in DB-loaded values; defense-in-depth at parse and
  Spring layers (`f8e92f7`).
- **Independent members private from impersonation (BOLA).** Admin impersonation
  can no longer reach independent members' data; debt `linkedAccountId` lookups
  are member-scoped (`e2ad075`, `cb7cf49`).
- **Goal contributions IDOR.** `/api/family/goals/{id}/contributions` checks
  ownership and sharing visibility (`7236faa`); new manual contribution entries
  are bound to the correct member (`45670e0`).
- **MFA error semantics.** An invalid TOTP / recovery code returns `400`, not
  `401`, so the client doesn't trigger a refresh loop (`68649c5`).
- **Deactivated accounts.** Login/refresh rejects deactivated accounts to stop a
  401 loop (`2780573`).
- **Deleted accounts no longer reappear after sync.** Accounts soft-delete via
  `@SQLRestriction("deleted_at IS NULL")`; sync upserts in `SyncService`,
  `TradeRepublicSyncService`, and `CryptoExchangeSyncService` refuse to resurrect
  removed accounts (`9297246`).
- **Add-account no longer 502s.** Enable Banking polling capped at 4.5 s (3 ×
  1.5 s) instead of 24 s; an unlinked session returns `[]` and the requisition is
  marked `FAILED` so the UI retry button takes over; nginx `/api` locations gained
  60 s read-timeout headroom (`9297246`).
- **FX conversion on prices.** Yahoo responses are FX-converted and the unsafe
  native-price fallback was removed (`f0e0ac1`).
- **Holdings.** VWAP deduplication, coherent live-price math, and correct
  capital-invested in the detail modal (`60c9a75`); null-quantity handling, Spring
  `@Transactional`, N+1 fix (`4f534ac`, `9b144d8`).
- **Dashboard net worth** reads invested capital from `balance_snapshot`
  (`d99e67b`).
- **Account balance edits.** Save errors surface in the month-end balance modal
  (`ecd2fcd`); `POST /{id}/history` is accepted for manual snapshots (`7922fb4`).
- **Family** — delete errors surface in the confirm dialog (`cb4e3a3`); admin
  shows "Administrateur" instead of "Compte indépendant" (`2d50db4`); lazy
  serialization, enum mapping, profile switching (`f152cb9`).
- **Finary** — `TOTP_REQUIRED` reported correctly when the session is flagged
  (`5dca6da`); auto-sync transaction handling and BoursoSync filter (`aaf770d`);
  show all bank connections in SyncAllModal (`ab4faa6`).
- **Auth cookies** SameSite=Lax for Safari iOS compatibility (`3e9e140`); CORS
  PATCH method allowed (`e25f57d`); detailed login error reporting + CORS trace
  logs (`2488771`, `4af867a`); local-network CORS via origin patterns (`2832da3`,
  `2859392`).
- **Wallet** — remove ticker from on-chain wallet accounts to prevent double price
  conversion (`916a128`).
- **Pre-existing test regression.** `SetupServiceTest` stubbed `findByUsername`
  while production calls `findByUsernameWithMember` (`9297246`).
- **Frontend** — TypeScript build errors (`7cb7a3d`); AddTransactionModal reset &
  holdings query (`b5b1d41`); export read-only transaction wrapping (`22dd58b`).

### Security

- **CORS hardening** (see *Fixed*): closed the wildcard-with-credentials
  vulnerability.
- **Broken-object-level-authorization (BOLA) hardening** for admin impersonation
  and member-scoped debt lookups (`e2ad075`, `cb7cf49`).
- **JWT invalidation on password change** via the `token_version` claim.
- **`SecureCookieProvider`** centralizes the environment-aware `Secure` flag for
  cookies (`9570661`).
- **Setup wizard** rejects `*` for CORS allowed origins (`627533a`).
- **Rate-limit buckets** for MFA challenge and data export (`a95d752`, `d0933ac`).
- **Persistent token theft detection** (rotating opaque tokens) (`6c1ae82`).

### Documentation

- **README & SECURITY.md** rewritten; license clarified (`e9185c9`, `25825e5`,
  `42ab61b`).
- **CLAUDE.md** files overhauled with conventions and "don'ts"; English-only rule
  enforced (`fb599a5`, `527a0a4`, `5c4d29d`).
- **IEEE-style 1.0.0 release docs** — SRS, SDD, SDS, STP, and User Manual under
  `docs/release/1.0.0/`.
- **ADRs** — first-launch wizard, tr-auth sidecar slim image, loan amortization on
  the fly, Yahoo FX conversion, and ETF composition via Boursorama (superseding the
  issuer-holdings approach) (`cd651e6`, `52aa8e5`, `627533a`, `ca49378`).
- **Feature notes** across bank sync, loans, setup wizard, intraday chart, manual
  transactions, CORS & cookies, accounts overview, ISIN→ticker, data export, MFA,
  multi-account family, admin recovery, and security insight.
- **Deployment** — GHCR image paths corrected (repo is `picsou-finance`)
  (`f5994f0`).

### Database migrations

- **V23** — `bourso_session` (`b953e55`)
- **V24** — manual transaction fields (`a33d53c`)
- **V25** — `setup_state` (`627533a`)
- **V26** — `setup_audit` (`627533a`)
- **V27** — loan extra fields (`52aa8e5`)
- **V28** — MFA + persistent sessions (`98243c5`)
- **V29** — `app_user.token_version` (`056a900`)
- **V30** — `account.deleted_at` soft-delete + index (`9297246`)
- **V31** — price-snapshot cleanup gate
- **V32** — `goal.history_start` (anchors trajectory charts)

[1.0.0]: https://github.com/Zoeille/picsou-finance/releases/tag/v1.0.0
