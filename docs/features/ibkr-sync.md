# Feature: Interactive Brokers (IBKR) sync

> Last updated: 2026-07-19

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
- `ibkr/client/IbkrFlexClient.java` — Flex Web Service HTTP + XML parsing (`IbkrFlexPort`)
- `port/IbkrFlexPort.java` — provider abstraction + `IbkrPosition` / `IbkrAccountData`
- `model/IbkrConnection.java`, `repository/IbkrConnectionRepository.java` — encrypted token + query id
- `db/migration/V56__ibkr_connection.sql` — `ibkr_connection` table
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

- **Base currency assumption.** `fxRateToBase` converts a position's native currency to the
  user's **IBKR base currency**, not necessarily EUR. `averageBuyIn` (and therefore the
  "invested" and PnL figures) is correct only when the IBKR base currency is EUR. **Net
  worth is unaffected** — it is recomputed live in EUR from tickers, never from the stored
  cost basis or IBKR prices. Document/expect a EUR IBKR base for accurate PnL.
- **LOT vs SUMMARY rows.** If the Flex Query has lots enabled, IBKR emits both a `SUMMARY`
  row and per-tax-lot `LOT` rows. The service keeps `SUMMARY`/absent and drops `LOT` to
  avoid double counting (`IbkrSyncService.isReportable`).
- **Asset coverage.** Equities/ETFs price well (ISIN→ticker→Yahoo). Instruments without a
  usable ISIN (some derivatives) fall back to the IBKR symbol and may not price — they then
  contribute 0 to the live balance (logged), same graceful degradation as Trade Republic.
  Cash lines (`assetCategory = CASH`) are skipped.
- **Async statement generation.** `GetStatement` can answer "still generating" (IBKR error
  code 1019); the client polls with backoff (matched on code *and* message text so a code
  change does not turn a transient wait into a hard failure).
- **Token expiry.** Flex tokens live 6h–1y. On failure the connection status flips to
  `ERROR`; the user regenerates the token and reconnects.
- **`GetStatement` `q` parameter.** Uses the **reference code** returned by `SendRequest`
  (not the query id). Verify against a real statement on first live run.

## Tests

- `IbkrFlexClientTest` — XML parser against a realistic fixture (exact IBKR attribute
  names, SUMMARY/LOT distinction, empty statement)
- `IbkrSyncServiceTest` — mapping: cost-basis → base-currency conversion, LOT filtering,
  "not connected" error

## Links

- Related ADR: [Flex Web Service for IBKR](../decisions/2026-07-19-ibkr-flex-web-service.md)
- IBKR Flex attribute reference: [csingley/ibflex](https://github.com/csingley/ibflex)
- **Frontend:** a tab on the Sync page (`IbkrTab.tsx`) — token + query id form → connect,
  then sync/disconnect — with `sync.ibkr.*` keys in fr/en/de/es. Verified by typecheck,
  ESLint and `IbkrTab.test.tsx` (3 render/interaction tests).
- **Deliberately skipped:** the `SetupService.INTEGRATIONS` / `IntegrationsService` registry
  entry (`"ibkr"`). The Sync-page tab is not gated on it (like the TR/Finary tabs), and
  adding it would surface an unlabeled toggle in the setup wizard.
- **Not yet exercised against a live IBKR account** — the parser is fixture-tested; a real
  Flex statement is the true end-to-end validation (verify the `GetStatement` `q` param and
  error codes on first live run).
