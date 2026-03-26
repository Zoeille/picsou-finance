package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final SyncService syncService;
    private final TradeRepublicSyncService trSyncService;
    private final PriceService priceService;

    public SchedulerService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        SyncService syncService,
        TradeRepublicSyncService trSyncService,
        PriceService priceService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.syncService = syncService;
        this.trSyncService = trSyncService;
        this.priceService = priceService;
    }

    /**
     * Daily at 08:00: Re-sync all linked bank accounts (Enable Banking + Trade Republic).
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyBankSync() {
        log.info("Starting daily bank sync");
        try {
            syncService.resyncAll();
        } catch (Exception ex) {
            log.error("Daily Enable Banking sync failed", ex);
        }
        trSyncService.resyncIfSessionActive();
    }

    /**
     * Daily at 08:05: Take a balance snapshot for all manual accounts
     * (so we have history even without manual updates).
     */
    @Scheduled(cron = "0 5 8 * * *")
    @Transactional
    public void dailyManualSnapshots() {
        log.info("Taking daily snapshots for manual accounts");
        LocalDate today = LocalDate.now();
        List<Account> manualAccounts = accountRepository.findAllByOrderByCreatedAtAsc()
            .stream()
            .filter(Account::isManual)
            .toList();

        for (Account account : manualAccounts) {
            Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), today);
            if (existing.isEmpty()) {
                snapshotRepository.save(BalanceSnapshot.builder()
                    .account(account)
                    .date(today)
                    .balance(account.getCurrentBalance())
                    .build());
            }
        }

        log.debug("Snapshots taken for {} manual accounts", manualAccounts.size());
    }

    /**
     * Every hour: Refresh prices for accounts with tickers.
     */
    @Scheduled(fixedDelay = 3600000)
    public void refreshPrices() {
        Set<String> tickers = accountRepository.findByTickerIsNotNull()
            .stream()
            .map(Account::getTicker)
            .collect(Collectors.toSet());

        if (!tickers.isEmpty()) {
            log.debug("Refreshing prices for tickers: {}", tickers);
            priceService.refreshPrices(tickers);
        }
    }
}
