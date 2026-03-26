package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.port.PriceProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fetches stock/ETF prices from Yahoo Finance (unofficial, no API key needed).
 * Used for PEA/Compte-Titres positions with tickers like "IWDA.AS", "MC.PA", etc.
 *
 * Note: This is an unofficial API. For production use consider Alpha Vantage or similar.
 */
@Component
public class YahooFinancePriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(YahooFinancePriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Tickers that are handled by CoinGecko — we skip those
    private static final Set<String> CRYPTO_TICKERS = Set.of(
        "BTC", "ETH", "SOL", "BNB", "ADA", "XRP", "DOGE", "DOT", "MATIC", "AVAX"
    );

    private final WebClient webClient;

    public YahooFinancePriceProvider() {
        this.webClient = WebClient.builder()
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build();
    }

    @Override
    public boolean supports(String ticker) {
        return !CRYPTO_TICKERS.contains(ticker.toUpperCase());
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .collect(Collectors.toSet());

        if (supported.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        // Yahoo Finance is fetched per-ticker (no batch endpoint for EUR conversion)
        for (String ticker : supported) {
            try {
                BigDecimal price = fetchSinglePrice(ticker);
                if (price != null) result.put(ticker.toUpperCase(), price);
            } catch (Exception ex) {
                log.warn("Yahoo Finance price fetch failed for {}: {}", ticker, ex.getMessage());
            }
        }

        return result;
    }

    private BigDecimal fetchSinglePrice(String ticker) {
        YahooResponse response = webClient.get()
            .uri("/v8/finance/chart/{ticker}?range=1d&interval=1d", ticker)
            .retrieve()
            .bodyToMono(YahooResponse.class)
            .timeout(TIMEOUT)
            .block();

        if (response == null || response.chart() == null || response.chart().result() == null
            || response.chart().result().isEmpty()) {
            return null;
        }

        var result = response.chart().result().get(0);
        if (result.meta() == null) return null;

        double price = result.meta().regularMarketPrice();
        if (price <= 0) return null;

        // If ticker already includes EUR currency (e.g. .PA, .AS), price is in EUR
        // If it's a USD-denominated asset, we'd need FX conversion (simplified: use as-is for now)
        return BigDecimal.valueOf(price);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YahooResponse(Chart chart) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<ChartResult> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResult(Meta meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(double regularMarketPrice, String currency) {}
}
