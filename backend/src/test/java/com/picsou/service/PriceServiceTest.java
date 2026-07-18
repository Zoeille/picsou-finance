package com.picsou.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the per-ticker guard in {@code backfillHistoricalPrices}, which exists for two reasons
 * that are easy to undo by accident.
 *
 * <p>It must <b>continue</b>: this runs from {@code PriceBackfillRunner}, an
 * {@code ApplicationRunner}, so an escaping exception fails Spring Boot startup outright.
 *
 * <p>And it must log at <b>ERROR</b>: the price providers swallow expected upstream failures and
 * return an empty map, so anything thrown here is a genuine bug. Logging it at WARN would
 * re-hide precisely what {@code CoinGeckoPriceProvider} was changed to rethrow.
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock CoinGeckoPriceProvider coinGecko;
    @Mock YahooFinancePriceProvider yahoo;
    @Mock PriceSnapshotRepository priceSnapshotRepository;

    @InjectMocks PriceService priceService;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PriceService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void backfill_continuesPastAFailingTicker_andLogsItAtError() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("ETH")).thenReturn(true);
        // BTC blows up with a genuine bug; ETH must still be backfilled.
        when(coinGecko.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenThrow(new IllegalStateException("a real bug"));
        when(coinGecko.getHistoricalPricesEur(eq("ETH"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("3000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        int saved = assertThatNoStartupFailure(() ->
            priceService.backfillHistoricalPrices(new java.util.LinkedHashSet<>(List.of("BTC", "ETH")), from));

        // The good ticker still landed -- the loop was not aborted.
        assertThat(saved).isEqualTo(1);
        verify(priceSnapshotRepository).save(any());

        // ERROR, not WARN: a bug here must not be quietly downgraded. Two lines now -- the
        // per-ticker failure and the run summary.
        assertThat(eventsAt(Level.ERROR)).hasSize(2);
        assertThat(eventsAt(Level.ERROR).get(0).getFormattedMessage()).contains("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();

        // The returned count alone can't distinguish "1 of 1" from "1 of 100", so the run
        // summary has to carry the failure ratio.
        assertThat(eventsAt(Level.ERROR).get(1).getFormattedMessage())
            .contains("1 of 2 tickers failing");
    }

    @Test
    void backfill_routesToYahoo_forTickersCoinGeckoDoesNotSupport() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getHistoricalPricesEur(eq("AAPL"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("200")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isEqualTo(1);
    }

    /** Runs the backfill and fails loudly if it throws — the ApplicationRunner contract. */
    private int assertThatNoStartupFailure(java.util.function.Supplier<Integer> backfill) {
        var result = new int[1];
        assertThatCode(() -> result[0] = backfill.get())
            .as("backfill must never propagate; PriceBackfillRunner would fail Spring Boot startup")
            .doesNotThrowAnyException();
        return result[0];
    }
}
