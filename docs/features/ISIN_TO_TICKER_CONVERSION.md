# Feature: ISIN to Yahoo Finance Ticker Conversion

> Last updated: 2026-07-04

## Context

Trade Republic returns account holdings with ISIN codes (e.g., `IE00BYVQ9F29`), but Yahoo Finance expects ticker symbols (e.g., `IWDA.AS`). This feature converts ISINs to Yahoo-compatible tickers via the OpenFIGI API, and also resolves the display name (e.g., "ISHARES CORE MSCI WORLD") for the frontend.

The converter is shared by two callers: the **Trade Republic sync** (its original use) and **manual transaction entry**, where a user can type an ISIN instead of a ticker in the *Add transaction* form (see [manual-transactions.md](./manual-transactions.md)). Both resolve at write time so an ISIN entry and the equivalent ticker entry collapse into one position.

## How it works

### Key files

- `backend/src/main/java/com/picsou/adapter/OpenFigiIsinConverter.java` — ISIN→ticker+name conversion via OpenFIGI `/v3/mapping` API; also exposes `public static boolean isIsin(String)`, the 12-char ISIN detector reused by callers to decide whether to resolve
- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java` — calls `resolve()` during sync, stores ticker and name
- `backend/src/main/java/com/picsou/service/ManualTransactionService.java` — calls `isIsin()` + `resolve()` when a user enters an instrument by ISIN in the *Add transaction* form (`applyInstrumentFields`)
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — rejects unconvertible ISINs via regex in `supports()`
- `frontend/src/components/shared/HoldingsCard.tsx` — displays name in title, ticker in square badge

### Flow

```
TR WebSocket → TrPosition(isin)
    ↓
TradeRepublicSyncService.upsertAccount()
    ↓
openFigiIsinConverter.resolve(isin)
    ↓
POST /v3/mapping  body: [{"idType":"ID_ISIN","idValue":"IE00BYVQ9F29"}]
    ↓
OpenFIGI returns array of results with ticker + exchCode + name
    ↓
pickBest() selects best exchange → composes ticker + Yahoo suffix
    ↓
Returns TickerResult(ticker="IWDA.AS", name="ISHARES CORE MSCI WORLD")
    ↓
Stored as AccountHolding.ticker + AccountHolding.name
    ↓
Frontend: h.name ?? h.ticker → shows name, falls back to ticker
```

## TR-native crypto ISIN short-circuit

Trade Republic's on-platform crypto (Bitcoin, Ethereum, etc. held directly, not via an ETC) uses internal ISINs of the form `XF000<SYMBOL><digits>` (e.g. `XF000BTC0017`, `XF000SOL0042`) that are not real market instruments — OpenFIGI never resolves them. `resolve()` checks the cache, then detects this pattern before the OpenFIGI call, parses `<SYMBOL>` out generically (not a hardcoded per-coin list — see GH issue #22), and validates it against the injected `CoinGeckoPriceProvider.supports()`. If known it returns `TickerResult(symbol, coinGecko.displayName(symbol))` and caches it. Returning the parsed symbol as the **ticker** (not just the name) is what makes the holding price-resolvable via `CoinGeckoPriceProvider` afterwards — the earlier version only fixed the display name and left the ticker as the fake ISIN. An unrecognized symbol logs one warning and falls through to the normal OpenFIGI path (which will still miss, same as before this feature); because that miss is cached, the warning fires once per holding, not on every `resolve()`.

Both the "is this a known crypto?" check and the display name come from `CoinGeckoPriceProvider`'s single `TICKER_TO_ID` registry (`displayName()` title-cases the CoinGecko coin id, so `MATIC` → "Matic Network") — there is no second per-coin map to keep in sync, and every known coin gets a real name, not just BTC/ETH. The converter takes `CoinGeckoPriceProvider` as a constructor dependency rather than calling a static method, so it stays consistent with whatever the price provider actually supports.

`resolve()` normalizes the input (`trim().toUpperCase(Locale.ROOT)`) once at the top and reuses that value for the cache key, the crypto-symbol match, and the OpenFIGI fallback ticker — earlier the cache/fallback used the raw input, so case/whitespace variants of the same ISIN (or a real ISIN differing only by case) created duplicate cache entries and duplicate OpenFIGI calls. `Locale.ROOT` avoids the Turkish-locale `i`/`İ` hazard on `toUpperCase`.

The `XF000` marker itself lives in one place: `OpenFigiIsinConverter.isTrCryptoIsin()` (prefix-based, case-insensitive), which `TradeRepublicAdapter` also calls to route these holdings to TR's own exchange (`TRD0`). The two TR-crypto detection sites (the adapter's exchange choice and this converter's parse) share that predicate/prefix so they can't drift.

**Backfill:** because the resolved ticker is persisted on each `Transaction`, manual crypto transactions entered *before* this behavior existed carry the fake ISIN (`XF000BTC0017`) as their ticker and would no longer aggregate with new `BTC` rows (`HoldingComputeService` groups by exact ticker). Migration `V38__backfill_tr_crypto_transaction_tickers.sql` rewrites those historical rows to the resolved symbol for the crypto set known at that release; derived `account_holding` rows self-heal on the next recompute.

## Exchange selection logic

`pickBest()` selects the Yahoo Finance ticker from multiple OpenFIGI results:

1. **Home exchange** — based on ISIN country prefix (`US`→US, `HK`→HK, `DE`→GY, etc.)
2. **US OTC/ADR** — for non-US ISINs, US listings often have best Yahoo coverage
3. **EU exchanges** — NA (Amsterdam), FP (Paris), GY/GR (Germany), LN (London)
4. **Any known exchange** — fallback

OpenFIGI `exchCode` is mapped to Yahoo suffix (e.g., `GY`→`.DE`, `NA`→`.AS`, `FP`→`.PA`, `HK`→`.HK`).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| OpenFIGI `/v3/mapping` endpoint | Direct identifier lookup, returns structured results with exchCode and name | `/v3/search` (keyword-based, different request/response format) |
| `TickerResult` record (ticker + name) | Frontend needs display name; avoids a second API call | Separate name lookup endpoint |
| Home exchange preference by ISIN country | US stocks get US tickers (better Yahoo coverage), HK stocks get `.HK`, etc. | Always EU exchanges → `NVD.DE` for NVIDIA works but less reliable |
| In-memory `ConcurrentHashMap` cache | Avoids repeated API calls during bulk sync | Database caching (adds complexity for ephemeral data) |
| Sentinel-free caching (store `TickerResult` or null via `Map.get()`) | Clean null check | Sentinel string `"ISIN"` (used previously, more error-prone) |

## Gotchas / Pitfalls

- **Wrong endpoint = silent failure**. The old code used `/v3/search` with a malformed body. OpenFIGI returns `400` but WebClient doesn't throw — it returns null data. Always verify with `curl` against the API when debugging.
- **`Map.of()` has a 10-entry limit**. Use `Map.ofEntries()` for the exchange suffix maps (30+ entries).
- **`useMemo` before conditional return**. React hooks must not be after `if (!data) return`. In `DashboardPage`, the `historyForRange` memo must be computed before the early return.
- **Yahoo Finance rejects ISIN-format strings**. `YahooFinancePriceProvider.supports()` uses regex `[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]` to detect 12-char ISINs and returns false. Unconverted ISINs never get price data.
- **Deduplication aggregates by ticker**. Multiple ISINs mapping to the same ticker are merged in `TradeRepublicSyncService` via `Map.merge()`. The name from the first ISIN wins.
- **Some tickers may not exist on Yahoo Finance**. German-listed tickers like `6RJ0.DE` (internal Bloomberg ID) may not resolve. The home-exchange-first strategy mitigates this.

## Tests

- `OpenFigiIsinConverterTest` — 4 unit tests covering the `isIsin()` detector (valid ISINs, case/whitespace normalization, rejects tickers and non-ISIN strings, rejects null/blank), plus 3 covering the TR-native crypto short-circuit: BTC/ETH ticker+name, a generic symbol (SOL) to prove it isn't hardcoded per coin, and case/whitespace normalization consistency. The network-bound OpenFIGI fallback path still has no unit test (WebClient mock setup is complex); callers that use it (`ManualTransactionServiceTest`) mock the converter instead.
- Manual verification with `curl` against OpenFIGI API:
  - `US0378331005` (Apple) → `AAPL`
  - `IE00B4L5Y983` (iShares MSCI World) → `IWDA.AS`
  - `KYG9830T1067` (Xiaomi) → `1810.HK`
  - `DE0007100000` (Mercedes-Benz) → `MBG.DE`
- Backend tests: `mvn test` passes (`GoalServiceTest`)

## Links

- Related feature: [price-service.md](./price-service.md) (price lookups)
- Related feature: [trade-republic.md](./trade-republic.md) (TR sync)
- No ADR needed — this is an adapter for external data transformation
