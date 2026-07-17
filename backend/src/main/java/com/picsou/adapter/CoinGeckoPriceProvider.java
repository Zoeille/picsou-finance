package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.picsou.port.PriceProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private static final Map<String, String> TICKER_TO_ID = Map.ofEntries(
        Map.entry("BTC", "bitcoin"),
        Map.entry("ETH", "ethereum"),
        Map.entry("SOL", "solana"),
        Map.entry("BNB", "binancecoin"),
        Map.entry("ADA", "cardano"),
        Map.entry("XRP", "ripple"),
        Map.entry("DOGE", "dogecoin"),
        Map.entry("DOT", "polkadot"),
        Map.entry("MATIC", "matic-network"),
        Map.entry("POL", "polygon-ecosystem-token"),
        Map.entry("AVAX", "avalanche-2"),
        Map.entry("USDT", "tether"),
        Map.entry("USDC", "usd-coin"),
        Map.entry("DAI", "dai"),
        Map.entry("EURC", "euro-coin"),
        Map.entry("LINK", "chainlink"),
        Map.entry("UNI", "uniswap"),
        Map.entry("ATOM", "cosmos"),
        Map.entry("LTC", "litecoin"),
        Map.entry("NEAR", "near"),
        Map.entry("ARB", "arbitrum"),
        Map.entry("OP", "optimism"),
        Map.entry("SHIB", "shiba-inu"),
        Map.entry("PEPE", "pepe"),
        Map.entry("SUI", "sui")
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
        return ticker != null && TICKER_TO_ID.containsKey(ticker.toUpperCase(Locale.ROOT));
    }

    /**
     * A human-readable display name for a known crypto {@code ticker}, derived from its
     * CoinGecko coin id (e.g. BTC &rarr; "Bitcoin", MATIC &rarr; "Matic Network"), or
     * {@code null} if the ticker is unknown. Keeps crypto naming in the single
     * {@link #TICKER_TO_ID} registry rather than a second per-coin map elsewhere; used by
     * {@link OpenFigiIsinConverter} to name Trade Republic's on-platform crypto holdings.
     */
    public String displayName(String ticker) {
        if (ticker == null) return null;
        String coinId = TICKER_TO_ID.get(ticker.toUpperCase(Locale.ROOT));
        if (coinId == null) return null;
        return Arrays.stream(coinId.split("-"))
            .filter(word -> !word.isBlank())
            .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(java.util.stream.Collectors.joining(" "));
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

    /**
     * Fetch hourly prices for a crypto ticker from CoinGecko over the last 24H.
     * CoinGecko's market_chart/range returns hourly data for ranges < 90 days.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        String coinId = TICKER_TO_ID.get(ticker.toUpperCase());
        if (coinId == null) return Map.of();

        try {
            long fromEpoch = from.atZone(ZoneOffset.UTC).toEpochSecond();
            long toEpoch = to.atZone(ZoneOffset.UTC).toEpochSecond();

            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}/market_chart/range")
                    .queryParam("vs_currency", "eur")
                    .queryParam("from", fromEpoch)
                    .queryParam("to", toEpoch)
                    .build(coinId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return Map.of();

            List<?> rawPrices = (List<?>) response.getOrDefault("prices", List.of());
            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();

            for (Object entry : rawPrices) {
                List<?> pair = (List<?>) entry;
                if (pair.size() >= 2) {
                    long timestamp = ((Number) pair.get(0)).longValue();
                    double price = ((Number) pair.get(1)).doubleValue();
                    LocalDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
                    if (!dt.isBefore(from) && !dt.isAfter(to) && price > 0) {
                        prices.put(dt, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                    }
                }
            }

            log.debug("Fetched {} intraday prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (Exception ex) {
            log.warn("CoinGecko intraday price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        String coinId = TICKER_TO_ID.get(ticker.toUpperCase());
        if (coinId == null) return Map.of();

        try {
            long fromEpoch = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long toEpoch = to.atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}/market_chart/range")
                    .queryParam("vs_currency", "eur")
                    .queryParam("from", fromEpoch)
                    .queryParam("to", toEpoch)
                    .build(coinId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return Map.of();

            List<?> rawPrices = (List<?>) response.getOrDefault("prices", List.of());
            Map<LocalDate, BigDecimal> prices = new HashMap<>();

            for (Object entry : rawPrices) {
                List<?> pair = (List<?>) entry;
                if (pair.size() >= 2) {
                    long timestamp = ((Number) pair.get(0)).longValue();
                    double price = ((Number) pair.get(1)).doubleValue();
                    LocalDate date = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
                    if (!date.isBefore(from) && !date.isAfter(to) && price > 0) {
                        prices.put(date, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                    }
                }
            }

            log.debug("Fetched {} historical prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (Exception ex) {
            log.warn("CoinGecko historical price fetch failed for {}: {}", ticker, ex.getMessage());
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
