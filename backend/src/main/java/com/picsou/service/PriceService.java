package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;
    private final PriceSnapshotRepository priceSnapshotRepository;

    // Simple in-memory price cache: ticker → (price, cachedAt)
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public PriceService(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo,
                        PriceSnapshotRepository priceSnapshotRepository) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    /**
     * Returns EUR price for the given ticker.
     * Returns BigDecimal.ONE if ticker is "EUR" (no conversion needed).
     * Returns null if price unavailable.
     */
    public BigDecimal getPriceEur(String ticker) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return BigDecimal.ONE;
        }

        String upper = ticker.toUpperCase(Locale.ROOT);

        // Check cache
        CachedPrice cached = priceCache.get(upper);
        if (cached != null && !cached.isExpired()) {
            return cached.price();
        }

        // Fetch from appropriate provider
        Set<String> singleTicker = Set.of(upper);
        Map<String, BigDecimal> prices;

        if (coinGecko.supports(upper)) {
            prices = coinGecko.getPricesEur(singleTicker);
        } else {
            prices = yahoo.getPricesEur(singleTicker);
        }

        BigDecimal price = prices.get(upper);
        if (price != null) {
            priceCache.put(upper, new CachedPrice(price, Instant.now()));
            return price;
        }

        return null;
    }

    /** Bulk fetch and refresh cache for all provided tickers. */
    public Map<String, BigDecimal> refreshPrices(Set<String> tickers) {
        if (tickers.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        Set<String> cryptoTickers = new HashSet<>();
        Set<String> stockTickers = new HashSet<>();

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase(Locale.ROOT);
            if ("EUR".equals(upper)) {
                result.put(upper, BigDecimal.ONE);
            } else if (coinGecko.supports(upper)) {
                cryptoTickers.add(upper);
            } else {
                stockTickers.add(upper);
            }
        }

        if (!cryptoTickers.isEmpty()) {
            coinGecko.getPricesEur(cryptoTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                result.put(k, v);
            });
        }

        if (!stockTickers.isEmpty()) {
            yahoo.getPricesEur(stockTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                result.put(k, v);
            });
        }

        log.debug("Refreshed prices for {} tickers", result.size());

        // Persist daily price snapshots
        LocalDate today = LocalDate.now();
        for (var entry : result.entrySet()) {
            if ("EUR".equals(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            Optional<PriceSnapshot> existing = priceSnapshotRepository.findByTickerAndDate(entry.getKey(), today);
            if (existing.isPresent()) {
                existing.get().setPriceEur(entry.getValue());
                priceSnapshotRepository.save(existing.get());
            } else {
                priceSnapshotRepository.save(PriceSnapshot.builder()
                    .ticker(entry.getKey())
                    .date(today)
                    .priceEur(entry.getValue())
                    .build());
            }
        }

        return result;
    }

    /** Convert an account's balance to EUR using its currency/ticker. */
    public BigDecimal toEur(BigDecimal balance, String currency, String ticker) {
        if (balance == null) return BigDecimal.ZERO;

        // Already in EUR
        if ("EUR".equalsIgnoreCase(currency) && (ticker == null || ticker.isBlank())) {
            return balance;
        }

        // Use ticker if available (more specific), else use currency
        String symbol = (ticker != null && !ticker.isBlank()) ? ticker : currency;
        BigDecimal price = getPriceEur(symbol);

        if (price == null) {
            // The returned number is now WRONG, not merely missing: an unconverted USD or
            // GBP balance flows into net worth and its snapshots as though it were EUR.
            // ERROR, because unlike a missing crypto price (which the wallet sync refuses to
            // record) this one silently corrupts a figure the user reads as authoritative.
            //
            // Deliberately NOT thrown, and deliberately still returning the raw balance:
            // toEur backs liveBalanceEur, the dashboard and the history charts, so throwing
            // would 500 all of them on one missing FX rate, and substituting zero would
            // understate net worth just as silently. Changing the number either way shifts
            // every user's totals; making the failure loud does not.
            log.error("No EUR rate for {} -- returning the balance UNCONVERTED, so any total "
                + "including it is wrong until the rate is available", symbol);
            return balance;
        }

        return balance.multiply(price);
    }

    /**
     * Backfill historical prices for the given tickers from external APIs.
     * Fetches daily prices for the last 12 months and saves as PriceSnapshots.
     * Skips dates that already have a snapshot.
     */
    public int backfillHistoricalPrices(Set<String> tickers, LocalDate from) {
        LocalDate to = LocalDate.now();
        int saved = 0;
        int failed = 0;

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase(Locale.ROOT);
            if ("EUR".equals(upper)) continue;

            // Guard per ticker: this runs from PriceBackfillRunner, an ApplicationRunner, so
            // an unguarded throw here would fail Spring Boot startup outright.
            try {
                Map<LocalDate, BigDecimal> prices;
                if (coinGecko.supports(upper)) {
                    prices = coinGecko.getHistoricalPricesEur(upper, from, to);
                } else {
                    prices = yahoo.getHistoricalPricesEur(upper, from, to);
                }

                for (var entry : prices.entrySet()) {
                    if (priceSnapshotRepository.findByTickerAndDate(upper, entry.getKey()).isEmpty()) {
                        priceSnapshotRepository.save(PriceSnapshot.builder()
                            .ticker(upper)
                            .date(entry.getKey())
                            .priceEur(entry.getValue())
                            .build());
                        saved++;
                    }
                }

                log.info("Backfilled {} prices for {}", prices.size(), upper);
            } catch (Exception ex) {
                // ERROR, not WARN: the providers return an empty map for expected upstream
                // failures, so anything thrown here is a genuine bug. Still skip rather than
                // propagate -- this runs from PriceBackfillRunner, an ApplicationRunner, where
                // an escaping exception fails Spring Boot startup outright.
                failed++;
                log.error("Historical price backfill failed for {} -- skipping it", upper, ex);
            }
        }

        // The per-ticker guard means a run can "succeed" having backfilled almost nothing,
        // and the returned count alone cannot distinguish "1 of 1" from "1 of 100". Summarise
        // so a sparse backfill is visible without grepping for individual failures.
        if (failed > 0) {
            log.error("Historical price backfill completed with {} of {} tickers failing ({} prices saved)",
                failed, tickers.size(), saved);
        } else {
            log.info("Historical price backfill completed for {} tickers ({} prices saved)",
                tickers.size(), saved);
        }

        return saved;
    }

    private record CachedPrice(BigDecimal price, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }

    /** Drop the in-memory price cache. Used by PriceFxCleanupRunner. */
    public void clearPriceCache() {
        priceCache.clear();
    }

    /**
     * Fetch intraday (hourly) prices for a ticker over the given time range.
     * Routes to CoinGecko for crypto, Yahoo Finance for stocks/ETFs.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return Map.of();
        }

        String upper = ticker.toUpperCase(Locale.ROOT);

        if (coinGecko.supports(upper)) {
            return coinGecko.getIntradayPricesEur(upper, from, to);
        } else {
            return yahoo.getIntradayPricesEur(upper, from, to);
        }
    }
}
