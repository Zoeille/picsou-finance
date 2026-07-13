# Feature: Price Service

> Last updated: 2026-05-19

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices are fetched from two free providers: CoinGecko for crypto and Yahoo Finance for stocks/ETFs. A 15-minute in-memory cache prevents hammering external APIs. The scheduler refreshes prices hourly for all accounts with tickers.

## How it works

### Provider routing

`PriceService.getPriceEur(ticker)` routes each ticker to the appropriate provider:

- **CoinGecko** (`CoinGeckoPriceProvider`): Handles crypto tickers (BTC, ETH, SOL, BNB, ADA, XRP, DOGE, DOT, MATIC, AVAX, LINK, UNI, ATOM, LTC, NEAR, ARB, OP, SHIB, PEPE, SUI). Uses the `/simple/price` endpoint with `vs_currencies=eur`. Supports batch queries (all tickers in one request).
- **Yahoo Finance** (`YahooFinancePriceProvider`): Handles everything CoinGecko does not -- stocks, ETFs, indices. Uses the unofficial `/v8/finance/chart/{ticker}` endpoint. Fetched per-ticker (no batch). Tickers like `IWDA.AS`, `MC.PA` are already EUR-denominated; foreign-currency tickers (USD/JPY/GBp/...) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Both providers implement `PriceProviderPort` with `supports(ticker)` and `getPricesEur(tickers)`.

### Caching

`PriceService` maintains a `ConcurrentHashMap<String, CachedPrice>` where the key is the uppercase ticker. Each entry stores the price and the cache timestamp. Entries expire after 900 seconds (15 minutes). On a cache miss, the price is fetched from the provider and cached.

`refreshPrices(Set<String> tickers)` bulk-fetches prices, partitions tickers into crypto and stock sets, calls each provider once, and updates the cache.

### Currency conversion

`PriceService.toEur(balance, currency, ticker)` converts an account balance to EUR:
- If currency is EUR and no ticker is set, returns the balance as-is.
- Otherwise, uses the ticker (preferred) or currency code to fetch a price, then multiplies.

### Scheduler

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`). It collects all tickers from accounts that have a non-null ticker, then calls `PriceService.refreshPrices()`. This keeps the cache warm for the dashboard.

### Key files

- `backend/src/main/java/com/picsou/service/PriceService.java` -- Price routing, caching, conversion
- `backend/src/main/java/com/picsou/service/SchedulerService.java` -- Hourly price refresh cron
- `backend/src/main/java/com/picsou/adapter/CoinGeckoPriceProvider.java` -- CoinGecko `/simple/price` with ticker-to-ID mapping
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}`
- `backend/src/main/java/com/picsou/port/PriceProviderPort.java` -- Port interface with `supports()` and `getPricesEur()`

### Flow

```
Dashboard loads --> needs EUR prices
        |
        v
PriceService.getPriceEur("BTC")
        |
        v
Check cache: CachedPrice for "BTC"
        |
        +-- hit (not expired) --> return cached price
        |
        +-- miss or expired
                |
                v
        CoinGeckoPriceProvider.supports("BTC") --> true
                |
                v
        GET api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur
                |
                v
        Cache result --> return price

Scheduler (every hour):
        |
        v
SchedulerService.refreshPrices()
        |
        v
Collect all non-null tickers from accounts
        |
        v
PriceService.refreshPrices(tickers)
        |
        v
Partition: crypto --> CoinGecko | stocks --> Yahoo
        |
        v
Bulk fetch, update cache
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| CoinGecko free tier | No API key needed, supports batch queries, reliable | CoinMarketCap (requires key) |
| Yahoo Finance (unofficial) | Free, covers European tickers (.PA, .AS) | Alpha Vantage (key required, limited) |
| 15-minute cache TTL | Balance between freshness and API rate limits | No cache (too many requests) or 1-hour cache (stale prices) |
| Hourly scheduler refresh | Keeps cache warm; ensures dashboard loads fast | Fetch on every dashboard request (slow) |
| Provider partition by `supports()` | Clean separation: CoinGecko gets crypto, Yahoo gets everything else | Hardcoded crypto ticker list (duplicated, harder to maintain) |

## Gotchas / Pitfalls

- **Yahoo Finance is unofficial**: The Yahoo Finance API is undocumented and can break or get rate-limited without notice. FX conversion is now applied inside `YahooFinancePriceProvider` using the `{CURRENCY}EUR=X` chart endpoint; `GBp`/`GBX` is treated as `GBP / 100`. If the FX call fails the ticker is omitted from the result map (no fabricated rate) — downstream consumers must tolerate a missing key.
- **CoinGecko rate limits**: The free tier has rate limits (~30 requests/minute). The batch endpoint mitigates this, but individual cache misses could accumulate. The 15-minute cache is essential.
- **Cache is in-memory only**: Prices are lost on restart. The scheduler will repopulate within one hour, but the first few dashboard loads after restart may trigger external API calls.
- **Provider priority is `supports()`-based**: CoinGecko checks a hardcoded ticker-to-ID map. If a new crypto asset is added (e.g. a new token), it must be added to `TICKER_TO_ID` in `CoinGeckoPriceProvider`.
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `PriceServiceTest` -- unit tests for caching, routing, conversion
- `CoinGeckoPriceProviderTest` -- unit tests for ticker mapping
- `YahooFinancePriceProviderTest` -- unit tests for response parsing

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
