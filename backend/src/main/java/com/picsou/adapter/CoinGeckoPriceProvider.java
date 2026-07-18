package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.picsou.port.PriceProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Fetches crypto prices from CoinGecko public API (no API key required for free tier).
 * Supports tickers like BTC, ETH, SOL, etc.
 */
@Component
public class CoinGeckoPriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HISTORY_TIMEOUT = Duration.ofSeconds(15);

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
        this(WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    CoinGeckoPriceProvider(WebClient webClient) {
        this.webClient = webClient;
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
        // Normalize once, up front: supports() is case-insensitive, so leaving mixed case
        // in here forces every later step to re-upper-case and makes the result map's keys
        // disagree with this set (which previously produced a false "no EUR price for 2 of
        // 2 tickers" warning, and a count computed over two different domains).
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        if (supported.isEmpty()) return Map.of();

        String ids = supported.stream()
            .map(TICKER_TO_ID::get)
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

            if (response == null) {
                log.warn("CoinGecko returned an empty body for spot prices {} -- returning no prices", supported);
                return Map.of();
            }

            Map<String, BigDecimal> result = new HashMap<>();
            for (String ticker : supported) {
                String coinId = TICKER_TO_ID.get(ticker);
                if (coinId != null && response.containsKey(coinId)) {
                    BigDecimal price = response.get(coinId).eur();
                    if (price != null) result.put(ticker, price);
                }
            }
            // Both sides are now normalized, so this is a like-for-like comparison. Gate on
            // the set rather than a size check, which could warn with an empty list.
            Set<String> missing = supported.stream()
                .filter(t -> !result.containsKey(t))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!missing.isEmpty()) {
                log.warn("CoinGecko had no EUR price for {} of {} requested tickers: {}",
                    missing.size(), supported.size(), missing);
            }
            return result;
        } catch (RuntimeException ex) {
            handleFetchFailure("spot prices", supported, TIMEOUT, ex);
            return Map.of();
        }
    }

    /**
     * Classifies a failed CoinGecko call, and decides whether it is ours to swallow.
     *
     * <p><b>Expected upstream failures</b> (HTTP error, unreachable API, timeout) are logged
     * and the caller returns no prices. That contract is load-bearing: callers read a missing
     * price as "not valued this cycle", never "not held" — {@code WalletSyncService} keys its
     * holdings prune on on-chain balances, so a CoinGecko blip leaves holdings and their cost
     * basis intact. Severity lives in the log, graded by whose problem it is.
     *
     * <p><b>Anything else</b> — an NPE, a {@link ClassCastException}, a parse defect — is
     * <em>rethrown</em>. Swallowing a real bug into an empty map hides it behind data that
     * merely looks unpriced. This is safe because the batch callers guard their own loops
     * ({@code SchedulerService.dailySnapshots} / {@code refreshPrices},
     * {@code PriceService.backfillHistoricalPrices}), so one bad ticker cannot abort a run,
     * and {@code WalletSyncService} already separates expected sync failures from bugs.
     *
     * <p>Note it unwraps first: {@code Mono.timeout()} signals a <em>checked</em>
     * {@link TimeoutException}, which {@code block()} wraps in a reactor
     * {@code ReactiveException}. Matching on the declared type without unwrapping would miss
     * timeouts entirely — the most common real CoinGecko failure.
     */
    private static void handleFetchFailure(String operation, Object context, Duration timeout, RuntimeException ex) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429) {
                log.warn("CoinGecko rate-limited (429) fetching {} for {} -- returning no prices", operation, context);
            } else if (http.getStatusCode().is5xxServerError()) {
                // Their outage, not our bug: WARN, matching how the rest of the codebase
                // grades expected external failures. These callers are on a scheduler and
                // run per-ticker, so an hours-long outage would otherwise pour ERROR lines
                // (each carrying a full HTML error page) into a self-hosted instance's log.
                log.warn("CoinGecko server error (HTTP {}) fetching {} for {} -- returning no prices: {}",
                    status, operation, context, lazyBody(http));
            } else if (status == 400 || status == 404) {
                // A malformed request or an unknown coin id points at a bad TICKER_TO_ID
                // entry -- something we can actually fix, so ERROR. The body is decoded
                // lazily via a supplier so a disabled level costs nothing.
                log.error("CoinGecko rejected the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, context, status, lazyBody(http));
            } else {
                // Other 4xx (401/403 free-tier restrictions, 451...) are the provider's
                // access policy, not a bug on our side: WARN like the other outage cases.
                log.warn("CoinGecko refused the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, context, status, lazyBody(http));
            }
        } else if (cause instanceof TimeoutException) {
            log.warn("CoinGecko {} request for {} timed out after {} -- returning no prices",
                operation, context, timeout);
        } else if (cause instanceof WebClientRequestException) {
            // Never reached the server at all: DNS failure, connection refused/reset, TLS
            // handshake. Same class of expected outage as a 5xx -- WARN, and without the
            // stacktrace, which would otherwise flood the log for the whole outage.
            log.warn("CoinGecko {} request for {} could not reach the API ({}) -- returning no prices",
                operation, context, cause.getMessage());
        } else {
            // Not an upstream failure -- an NPE, ClassCastException or parse defect on our
            // side. Rethrow rather than return an empty map: a bug that presents as "no
            // prices" is indistinguishable from a quiet outage and would never get fixed.
            throw ex;
        }
    }

    /**
     * Walks CoinGecko's {@code prices} field — documented as an array of
     * {@code [epochMillis, price]} pairs — handing each well-formed pair to {@code consumer}.
     *
     * <p>Every step is checked rather than cast. A shape change upstream (an object instead
     * of an array, string-encoded numbers, a short pair) must degrade to a warn and a skip:
     * since {@link #handleFetchFailure} now rethrows anything that is not an upstream
     * failure, a blind cast here would turn a CoinGecko format change into a
     * {@link ClassCastException} propagating into the daily snapshot batch.
     */
    private static void forEachPricePoint(
        Map<String, Object> response, String context, java.util.function.BiConsumer<Long, Double> consumer) {

        Object raw = response.getOrDefault("prices", List.of());
        if (!(raw instanceof List<?> rawPrices)) {
            log.warn("CoinGecko returned a non-list 'prices' field ({}) for {} -- returning no prices",
                raw == null ? "null" : raw.getClass().getSimpleName(), context);
            return;
        }

        int skipped = 0;
        for (Object entry : rawPrices) {
            if (!(entry instanceof List<?> pair) || pair.size() < 2
                || !(pair.get(0) instanceof Number timestamp)
                || !(pair.get(1) instanceof Number price)) {
                skipped++;
                continue;
            }
            consumer.accept(timestamp.longValue(), price.doubleValue());
        }

        // Once per call, not per entry: a wholesale format change would otherwise emit one
        // line per data point, thousands of them for a long range.
        if (skipped > 0) {
            log.warn("CoinGecko returned {} malformed price points (of {}) for {} -- skipped",
                skipped, rawPrices.size(), context);
        }
    }

    /**
     * Defers decoding the upstream error body until the log level is known to be enabled —
     * SLF4J only calls {@code toString()} on an argument it actually formats. Also caps it,
     * so one bad gateway's multi-kilobyte HTML page can't fill the log.
     */
    private static Object lazyBody(WebClientResponseException http) {
        return new Object() {
            @Override public String toString() {
                String body = http.getResponseBodyAsString();
                if (body == null || body.isBlank()) return "<empty body>";
                return body.length() <= 200 ? body : body.substring(0, 200) + "... (truncated)";
            }
        };
    }

    /**
     * Fetch hourly prices for a crypto ticker from CoinGecko over the last 24H.
     * CoinGecko's market_chart/range returns hourly data for ranges < 90 days.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        String coinId = TICKER_TO_ID.get(ticker.toUpperCase(Locale.ROOT));
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
                .timeout(HISTORY_TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for intraday prices of {} ({})", ticker, coinId);
                return Map.of();
            }

            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();
            forEachPricePoint(response, ticker + " (" + coinId + ")", (timestamp, price) -> {
                LocalDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
                if (!dt.isBefore(from) && !dt.isAfter(to) && price > 0) {
                    prices.put(dt, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                }
            });

            log.debug("Fetched {} intraday prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (RuntimeException ex) {
            handleFetchFailure("intraday prices", ticker + " (" + coinId + ")", HISTORY_TIMEOUT, ex);
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        String coinId = TICKER_TO_ID.get(ticker.toUpperCase(Locale.ROOT));
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
                .timeout(HISTORY_TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for historical prices of {} ({})", ticker, coinId);
                return Map.of();
            }

            Map<LocalDate, BigDecimal> prices = new HashMap<>();
            forEachPricePoint(response, ticker + " (" + coinId + ")", (timestamp, price) -> {
                LocalDate date = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.isBefore(from) && !date.isAfter(to) && price > 0) {
                    prices.put(date, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                }
            });

            log.debug("Fetched {} historical prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (RuntimeException ex) {
            handleFetchFailure("historical prices", ticker + " (" + coinId + ")", HISTORY_TIMEOUT, ex);
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
