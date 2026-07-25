# Feature: Interactive Brokers (IBKR) sync

> Last updated: 2026-07-21

## Context

Interactive Brokers is the reference broker for anyone holding US/global equities and
ETFs, and it was the main gap in Picsou's brokerage coverage (which already had Trade
Republic and Finary). This feature pulls IBKR **open positions** once a day and maps them
onto Picsou accounts + holdings, so an IBKR portfolio shows up in net worth exactly like
any other holdings account.

## How it works

IBKR exposes read-only portfolio data through the **Flex Web Service**: the user builds an
"Open Positions" Flex Query and generates a token in Client Portal, both of which they
paste into Picsou once. Sync is a two-step HTTP flow — `SendRequest` returns a reference
code, `GetStatement` returns the statement XML once IBKR has generated it (polled with
backoff). Data is end-of-day, which matches Picsou's daily snapshot cadence.

Parsed positions become `AccountHolding` rows under one `Account` per IBKR account id
(`external_account_id = "ibkr_<accountId>"`, type `COMPTE_TITRES`). Valuation reuses the
existing live-price path: the dashboard recomputes each holding's EUR value from its ticker
via `PriceService` (Yahoo/CoinGecko, FX-converted). The IBKR-reported prices are **not**
trusted for EUR valuation.

### Key files

- `controller/IbkrController.java` — `/api/ibkr/connect|status|sync|connection`
- `service/IbkrSyncService.java` — connect/status/sync + position→holding mapping
  (lives in `com.picsou.service` with the other broker sync services)
- `service/IbkrStatusWriter.java` — persists the `ERROR` status in its own
  `REQUIRES_NEW` transaction so it survives the sync transaction's rollback
- `ibkr/client/IbkrFlexClient.java` — Flex Web Service HTTP + XML parsing (`IbkrFlexPort`)
- `port/IbkrFlexPort.java` — provider abstraction + `IbkrPosition` / `IbkrAccountData`
- `model/IbkrConnection.java`, `repository/IbkrConnectionRepository.java` — encrypted token + query id
- `db/migration/V57__ibkr_connection.sql` — `ibkr_connection` table
- Reuses: `OpenFigiIsinConverter` (ISIN→ticker), `HoldingDedup` (VWAP), `CryptoEncryption`,
  `AccountService.liveBalanceEur` (net-worth valuation), `SchedulerService` (daily auto-sync)
- `frontend/src/pages/sync/IbkrTab.tsx` — Sync-page connection tab (token + query id form →
  connect, then sync/disconnect); `sync.ibkr.*` i18n keys in all four locales

### Flow

```
Client Portal: build Open Positions Flex Query + generate token
        │  (token + queryId pasted once)
        ▼
POST /api/ibkr/connect ─► IbkrConnection (token + queryId AES-256-GCM encrypted)
        │
POST /api/ibkr/sync ─► IbkrFlexClient
        │   SendRequest?t&q&v=3            → <ReferenceCode>
        │   GetStatement?t&q=refCode&v=3   → poll until <FlexQueryResponse>
        ▼
   parse <OpenPosition> rows  ─► group by accountId
        ▼
   per account: delete+recompute holdings (ISIN→ticker, VWAP de-dup,
                cost basis → base ccy via fxRateToBase)
        ▼
   liveBalanceEur (Yahoo/CoinGecko) ─► currentBalance + daily snapshot
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Flex Web Service | Read-only token, no gateway/desktop process to run, EOD data fits daily snapshots | Client Portal API (needs a running gateway + daily 2FA re-auth); TWS API (needs TWS/IB Gateway desktop open) |
| JDK DOM parser | Zero new dependency; mirrors the raw-`HttpClient` style of `FinaryApiClient` | `jackson-dataformat-xml` / JAXB (extra dependency for a flat, simple document) |
| Value live in EUR via ticker | Net worth stays correct regardless of the user's IBKR base currency | Trusting IBKR `markPrice`/`positionValueInBase` (base-currency dependent) |
| Store `averageBuyIn` as `costBasisPrice × fxRateToBase` | Best-effort EUR cost basis for the invested/PnL figures | Leaving it null (loses PnL for the common EUR-base case) |

See the [ADR](../decisions/2026-07-19-ibkr-flex-web-service.md) for the full API-choice rationale.

## Gotchas / Pitfalls

- **Base currency requirement (enforced).** `fxRateToBase` converts a position's native
  currency to the user's **IBKR base currency**, not necessarily EUR. `averageBuyIn` (and
  therefore the "invested" and PnL figures) is correct only when the IBKR base currency is
  EUR. **Net worth is unaffected** — it is recomputed live in EUR from tickers, never from
  the stored cost basis or IBKR prices. Since plan 003 (issue B), `IbkrSyncService`
  actively guards this instead of silently trusting it: it reads the account's base
  currency from the Flex statement's optional `AccountInformation` section (`currency`
  attribute — a section the user must enable on the Flex Query alongside Open Positions);
  if present and not `EUR`, the sync **throws `SyncException`** with a message telling the
  user to change the IBKR account's base currency in Account Settings, and **no holdings
  are persisted** for that account. If the statement has no `AccountInformation` section at
  all (not enabled on the query), the base currency is unknown — sync proceeds assuming EUR
  as before, logging one WARN per account per sync so this is visible on day 1. **Owner
  operational note:** choosing EUR as the IBKR account base currency at account opening
  neutralizes this entirely.
- **LOT vs SUMMARY rows.** If the Flex Query has lots enabled, IBKR emits both a `SUMMARY`
  row and per-tax-lot `LOT` rows. The service keeps `SUMMARY`/absent and drops `LOT` to
  avoid double counting (`IbkrSyncService.isReportable`).
- **Asset coverage.** `isReportable` allowlists `assetCategory` in `STK` (covers stocks
  *and* ETFs — IBKR has no separate ETF category), `FUND`, `BOND`, `CRYPTO`, or absent
  (case-insensitive). Everything else — options (`OPT`), futures (`FUT`), future options
  (`FOP`), CFDs, warrants, ... — is skipped with a WARN log naming the category and
  symbol, so day-1 real data surfaces any category the allowlist got wrong. This replaced
  an over-long-ticker guard alone, which does **not** catch derivatives: a standard OCC
  option symbol like `"SPY 240119C00470000"` is 19 chars, under the 30-char
  `account_holding.ticker` column limit, so it used to pass through as a mispriced stock
  holding (per-share cost basis with the contract multiplier ignored, live value null).
  The ticker-length guard is still kept as a backstop for whatever slips through the
  allowlist. Cash lines (`assetCategory = CASH`) and zero-quantity positions are skipped
  silently (expected, not a day-1 finding). Equities/ETFs price well (ISIN→ticker→Yahoo);
  instruments without a usable ISIN fall back to the IBKR symbol and may not price — they
  then contribute 0 to the live balance (logged), same graceful degradation as Trade
  Republic. A resolved ticker longer than the `account_holding.ticker` column (30 chars)
  is skipped rather than crashing the whole account's sync; the display name is clipped
  to 100 chars for the same reason.
- **Async statement generation.** `GetStatement` can answer "still generating" (IBKR error
  code 1019); the client polls with backoff (matched on code *and* message text so a code
  change does not turn a transient wait into a hard failure).
- **Token expiry.** Flex tokens live 6h–1y. On failure the connection status flips to
  `ERROR`; the user regenerates the token and reconnects. `IbkrSyncService` is
  `@Transactional`, so a plain save-then-rethrow in the catch block would be rolled back
  on the manual sync path (the exception marks the transaction rollback-only before it
  reaches the controller) — `IbkrStatusWriter.markError` runs in its own `REQUIRES_NEW`
  transaction instead, so the `ERROR` status commits independently of the outer
  rollback on both the manual and scheduled paths.
- **`GetStatement` `q` parameter.** Uses the **reference code** returned by `SendRequest`
  (not the query id). Confirmed against a live statement on 2026-07-21.

## Tests

- `IbkrFlexClientTest` — XML parser against a realistic fixture (exact IBKR attribute
  names, SUMMARY/LOT distinction, empty statement)
- `IbkrSyncServiceTest` — mapping: cost-basis → base-currency conversion, LOT filtering,
  "not connected" error, error-status persistence, derivative asset-category filtering,
  zero-net-quantity positions
- `HoldingDedupTest` — VWAP merge (shared with TR/Bourso), including the sign-aware
  cases IBKR can trigger (opposite-sign netting, netting to exactly zero)

## Links

- Related ADR: [Flex Web Service for IBKR](../decisions/2026-07-19-ibkr-flex-web-service.md)
- IBKR Flex attribute reference: [csingley/ibflex](https://github.com/csingley/ibflex)
- **Frontend:** a tab on the Sync page (`IbkrTab.tsx`) — token + query id form → connect,
  then sync/disconnect — with `sync.ibkr.*` keys in fr/en/de/es. Verified by typecheck,
  ESLint and `IbkrTab.test.tsx` (3 render/interaction tests).
- **Deliberately skipped:** the `SetupService.INTEGRATIONS` / `IntegrationsService` registry
  entry (`"ibkr"`). The Sync-page tab is not gated on it (like the TR/Finary tabs), and
  adding it would surface an unlabeled toggle in the setup wizard.
- **Validated against a live IBKR account (2026-07-21).** Full flow exercised on a real
  Flex statement (dev instance, real credentials): `SendRequest` → reference code →
  `GetStatement` returned the statement on the first poll; parsed 1 open position
  (fractional `STK` share) into one `ibkr_<accountId>` account. `AccountInformation`
  was present with `currency="EUR"`, so the base-currency guard took its happy path
  (no WARN). Cost basis matched (`invested = quantity × averageBuyIn` to the cent),
  live EUR valuation resolved via Yahoo, the daily `balance_snapshot` row was written,
  and an immediate re-sync was idempotent (same account row, still one holding, no
  duplicate snapshot thanks to the `(account_id, date)` unique constraint). Not yet
  observed live: the 1019 "still generating" poll path and token-expiry `ERROR`
  handling (fixture-tested only).
