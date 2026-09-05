package com.picsou.config;

import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/**
 * Automatically backfills historical prices on startup if holding tickers have no price history.
 * Idempotent — only fills gaps, skips dates that already have a snapshot.
 *
 * <p>Split by account type, like {@code SchedulerService.refreshPrices}: a coin held in a CRYPTO
 * account that CoinGecko cannot map must not be backfilled from Yahoo, where its symbol may be a
 * listed company's (STX: Stacks in the wallet, Seagate on Nasdaq). A year of the company's closes
 * under the coin's symbol would feed the range P&L and the last-known-price fallback until
 * someone deleted the rows by hand.
 */
@Component
public class PriceBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PriceBackfillRunner.class);

    private final PriceService priceService;
    private final AccountHoldingRepository holdingRepository;

    public PriceBackfillRunner(PriceService priceService, AccountHoldingRepository holdingRepository) {
        this.priceService = priceService;
        this.holdingRepository = holdingRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> cryptoTickers = new TreeSet<>(
            holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO));
        Set<String> otherTickers = new TreeSet<>(holdingRepository.findDistinctTickers());
        otherTickers.removeAll(cryptoTickers);

        if (cryptoTickers.isEmpty() && otherTickers.isEmpty()) {
            log.debug("No holding tickers found — skipping price backfill");
            return;
        }

        LocalDate from = LocalDate.now().minusMonths(12);
        int saved = 0;
        if (!cryptoTickers.isEmpty()) {
            saved += priceService.backfillHistoricalPrices(cryptoTickers, from, true);
        }
        if (!otherTickers.isEmpty()) {
            saved += priceService.backfillHistoricalPrices(otherTickers, from, false);
        }
        log.info("Price backfill complete: {} snapshots saved for {} tickers ({} crypto)",
            saved, cryptoTickers.size() + otherTickers.size(), cryptoTickers.size());
    }
}
