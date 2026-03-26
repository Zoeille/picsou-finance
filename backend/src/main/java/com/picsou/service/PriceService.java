package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;

    // Simple in-memory price cache: ticker → (price, cachedAt)
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public PriceService(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
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

        String upper = ticker.toUpperCase();

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
            String upper = ticker.toUpperCase();
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
            log.warn("No price available for symbol: {}, returning raw balance", symbol);
            return balance;
        }

        return balance.multiply(price);
    }

    private record CachedPrice(BigDecimal price, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }
}
