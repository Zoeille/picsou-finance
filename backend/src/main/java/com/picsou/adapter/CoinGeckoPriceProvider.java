package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.picsou.port.PriceProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * Fetches crypto prices from CoinGecko public API (no API key required for free tier).
 * Supports tickers like BTC, ETH, SOL, etc.
 */
@Component
public class CoinGeckoPriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Map from ticker (uppercase) → CoinGecko coin ID
    private static final Map<String, String> TICKER_TO_ID = Map.of(
        "BTC", "bitcoin",
        "ETH", "ethereum",
        "SOL", "solana",
        "BNB", "binancecoin",
        "ADA", "cardano",
        "XRP", "ripple",
        "DOGE", "dogecoin",
        "DOT", "polkadot",
        "MATIC", "matic-network",
        "AVAX", "avalanche-2"
    );

    private final WebClient webClient;

    public CoinGeckoPriceProvider() {
        this.webClient = WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override
    public boolean supports(String ticker) {
        return TICKER_TO_ID.containsKey(ticker.toUpperCase());
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .collect(java.util.stream.Collectors.toSet());

        if (supported.isEmpty()) return Map.of();

        String ids = supported.stream()
            .map(t -> TICKER_TO_ID.get(t.toUpperCase()))
            .filter(Objects::nonNull)
            .reduce((a, b) -> a + "," + b)
            .orElse("");

        try {
            Map<String, PriceData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/simple/price")
                    .queryParam("ids", ids)
                    .queryParam("vs_currencies", "eur")
                    .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, PriceData>>() {})
                .timeout(TIMEOUT)
                .block();

            if (response == null) return Map.of();

            Map<String, BigDecimal> result = new HashMap<>();
            for (String ticker : supported) {
                String coinId = TICKER_TO_ID.get(ticker.toUpperCase());
                if (coinId != null && response.containsKey(coinId)) {
                    BigDecimal price = response.get(coinId).eur();
                    if (price != null) result.put(ticker.toUpperCase(), price);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("CoinGecko price fetch failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    static class PriceData {
        private BigDecimal eur;

        @JsonAnySetter
        public void setField(String key, Object value) {
            if ("eur".equals(key) && value instanceof Number n) {
                this.eur = BigDecimal.valueOf(n.doubleValue());
            }
        }

        public BigDecimal eur() { return eur; }
    }
}
