package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the adapter's failure contract, which is the part of this class that repeatedly
 * regressed: an <em>expected</em> upstream failure must return no prices and be logged at a
 * severity matching whose problem it is, while a genuine bug must propagate rather than hide
 * behind an empty map.
 *
 * <p>Failures are injected as {@code Mono.error(...)} rather than by real I/O, which also
 * exercises the reactor wrapping that matters here: {@code block()} wraps a <em>checked</em>
 * exception (notably {@link TimeoutException}) in a {@code ReactiveException}, so the adapter
 * has to unwrap before classifying. Matching on declared types alone would silently stop
 * catching timeouts.
 */
class CoinGeckoPriceProviderTest {

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private CoinGeckoPriceProvider providerReturning(Mono<ClientResponse> response) {
        ExchangeFunction exchange = request -> response;
        return new CoinGeckoPriceProvider(WebClient.builder().exchangeFunction(exchange).build());
    }

    private CoinGeckoPriceProvider providerWithJson(String json) {
        return providerReturning(Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json).build()));
    }

    private CoinGeckoPriceProvider providerWithStatus(HttpStatus status, String body) {
        return providerReturning(Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body).build()));
    }

    private CoinGeckoPriceProvider providerFailingWith(Throwable ex) {
        return providerReturning(Mono.error(ex));
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void getPricesEur_parsesPrices_andKeysThemUpperCase() {
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0},\"ethereum\":{\"eur\":3000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("BTC", "ETH"));

        assertThat(prices).containsOnlyKeys("BTC", "ETH");
        assertThat(prices.get("BTC")).isEqualByComparingTo("50000");
        assertThat(prices.get("ETH")).isEqualByComparingTo("3000");
    }

    @Test
    void getPricesEur_acceptsLowerCaseTickers_andStillKeysResultsUpperCase() {
        // supports() is case-insensitive, so the port contract permits lower case. The
        // result must still be keyed upper-case, and no "missing ticker" warning should
        // fire on what is a completely successful fetch.
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("btc"));

        assertThat(prices).containsOnlyKeys("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();
    }

    @Test
    void getPricesEur_warnsOnlyForTheTickersActuallyMissing() {
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("BTC", "ETH"));

        assertThat(prices).containsOnlyKeys("BTC");
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("ETH").doesNotContain("BTC"));
    }

    @Test
    void resolvesEveryTickerAddedForTheEvmFanOut_toItsCoinGeckoId() {
        // POL/USDT/USDC/DAI/EURC were added alongside the EVM multichain work. A typo in a
        // coin id degrades silently: the request simply omits it, the price comes back
        // missing, and the asset drops out of the wallet total with only a generic warning.
        // Assert on the outgoing request so the mapping itself is pinned, not just the
        // stubbed response.
        Map<String, String> expectedIds = Map.of(
            "POL", "polygon-ecosystem-token",
            "USDT", "tether",
            "USDC", "usd-coin",
            "DAI", "dai",
            "EURC", "euro-coin");

        expectedIds.forEach((ticker, coinId) -> {
            var captured = new java.util.concurrent.atomic.AtomicReference<String>();
            ExchangeFunction exchange = request -> {
                captured.set(request.url().toString());
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"" + coinId + "\":{\"eur\":1.0}}").build());
            };
            var provider = new CoinGeckoPriceProvider(
                WebClient.builder().exchangeFunction(exchange).build());

            Map<String, BigDecimal> prices = provider.getPricesEur(Set.of(ticker));

            assertThat(captured.get())
                .as("%s must be requested as CoinGecko id '%s'", ticker, coinId)
                .contains("ids=" + coinId);
            assertThat(prices)
                .as("%s must resolve back to its ticker key", ticker)
                .containsKey(ticker);
        });
    }

    // ── Expected upstream failures: no prices, graded log, never thrown ───────

    @Test
    void rateLimit_returnsNoPrices_andWarns() {
        var provider = providerWithStatus(HttpStatus.TOO_MANY_REQUESTS, "{\"status\":\"rate limited\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("429"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void serverError_returnsNoPrices_andWarnsRatherThanErrors() {
        // Their outage, not our bug. These callers run on a scheduler and per-ticker, so
        // grading this ERROR would flood the log for the whole outage.
        var provider = providerWithStatus(HttpStatus.BAD_GATEWAY, "<html>bad gateway</html>");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void notFound_returnsNoPrices_andErrors() {
        // A 404 points at a bad TICKER_TO_ID coin id -- something we can actually fix.
        var provider = providerWithStatus(HttpStatus.NOT_FOUND, "{\"error\":\"unknown coin\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.ERROR)).hasSize(1);
    }

    @Test
    void forbidden_returnsNoPrices_andWarns() {
        // Free-tier access policy, not a bug on our side.
        var provider = providerWithStatus(HttpStatus.FORBIDDEN, "{\"error\":\"forbidden\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void timeout_returnsNoPrices_andWarns_despiteReactorWrapping() {
        // TimeoutException is CHECKED, so block() delivers it wrapped in a ReactiveException.
        // If the adapter matched on the declared type without unwrapping, this -- the most
        // common real CoinGecko failure -- would escape as an unhandled bug.
        var provider = providerFailingWith(new TimeoutException("simulated timeout"));

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).containsIgnoringCase("timed out"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void connectionFailure_returnsNoPrices_andWarns() {
        var provider = providerFailingWith(new WebClientRequestException(
            new IOException("connection refused"), org.springframework.http.HttpMethod.GET,
            URI.create("https://api.coingecko.com"), org.springframework.http.HttpHeaders.EMPTY));

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    // ── Genuine bugs propagate ────────────────────────────────────────────────

    @Test
    void unexpectedException_propagates_insteadOfBeingSwallowedAsNoPrices() {
        // The contract this class exists to defend: a bug that presents as "no prices" is
        // indistinguishable from a quiet outage and would never get fixed.
        var provider = providerFailingWith(new IllegalStateException("a real bug"));

        assertThatThrownBy(() -> provider.getPricesEur(Set.of("BTC")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("a real bug");
    }

    // ── Defensive parsing of the history payload ──────────────────────────────

    private static String pricePoint(LocalDate date, double price) {
        long millis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return "[" + millis + "," + price + "]";
    }

    @Test
    void getHistoricalPricesEur_parsesWellFormedPoints() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 3);
        var provider = providerWithJson("{\"prices\":[" + pricePoint(from, 100.0) + "]}");

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(prices.get(from)).isEqualByComparingTo("100");
    }

    @Test
    void getHistoricalPricesEur_skipsMalformedPoints_withoutThrowing() {
        // A CoinGecko format change must degrade to a warn and a skip. Since genuine bugs
        // now propagate, a blind cast here would abort the daily snapshot batch instead.
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 3);
        String json = "{\"prices\":["
            + pricePoint(from, 100.0) + ","
            + "[\"not-a-number\",\"also-not\"],"   // string-encoded
            + "[12345],"                            // short pair
            + "\"not-even-a-pair\""                 // wrong shape entirely
            + "]}";
        var provider = providerWithJson(json);

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("3 malformed"));
    }

    @Test
    void getHistoricalPricesEur_returnsEmpty_whenPricesFieldIsNotAList() {
        var provider = providerWithJson("{\"prices\":{\"unexpected\":\"object\"}}");

        assertThatCode(() -> assertThat(
            provider.getHistoricalPricesEur("BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)))
            .isEmpty()).doesNotThrowAnyException();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("non-list"));
    }

    @Test
    void getIntradayPricesEur_skipsMalformedPoints_withoutThrowing() {
        var from = LocalDate.of(2026, 1, 1).atStartOfDay();
        var to = from.plusDays(2);
        long millis = from.toInstant(ZoneOffset.UTC).toEpochMilli();
        var provider = providerWithJson(
            "{\"prices\":[[" + millis + ",100.0],[\"bad\",\"pair\"]]}");

        var prices = provider.getIntradayPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("1 malformed"));
    }
}
