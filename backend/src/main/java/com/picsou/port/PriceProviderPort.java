package com.picsou.port;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Port for fetching live asset prices.
 * Implement this interface to add a new price source (e.g. Alpha Vantage).
 */
public interface PriceProviderPort {

    /**
     * Returns prices in EUR for the given tickers.
     * Tickers not found in this provider return empty (caller tries next provider).
     */
    Map<String, BigDecimal> getPricesEur(Set<String> tickers);

    /** Whether this provider supports the given ticker. */
    boolean supports(String ticker);
}
