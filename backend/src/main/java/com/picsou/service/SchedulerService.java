package com.picsou.service;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final SyncService syncService;
    private final TradeRepublicSyncService trSyncService;
    private final BoursoSyncService boursoSyncService;
    private final PriceService priceService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletSyncService walletSyncService;
    private final FinaryApiSyncService finaryApiSyncService;

    public SchedulerService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        SyncService syncService,
        TradeRepublicSyncService trSyncService,
        BoursoSyncService boursoSyncService,
        PriceService priceService,
        CryptoExchangeSyncService cryptoExchangeSyncService,
        WalletSyncService walletSyncService,
        FinaryApiSyncService finaryApiSyncService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.syncService = syncService;
        this.trSyncService = trSyncService;
        this.boursoSyncService = boursoSyncService;
        this.priceService = priceService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletSyncService = walletSyncService;
        this.finaryApiSyncService = finaryApiSyncService;
    }

    /**
     * Daily at 08:00: Re-sync all linked bank accounts for every family member
     * (Enable Banking + Trade Republic + Crypto Exchanges + Wallets).
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyBankSync() {
        log.info("Starting daily bank sync for all members");
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            Long memberId = member.getId();
            log.info("Syncing member {}", memberId);

            try {
                syncService.resyncAll(memberId);
            } catch (Exception ex) {
                log.error("Daily Enable Banking sync failed for member {}", memberId, ex);
            }

            try {
                syncService.retryAllFailed(memberId);
            } catch (Exception ex) {
                log.error("Daily retry of FAILED Enable Banking sessions failed for member {}", memberId, ex);
            }

            trSyncService.resyncIfSessionActive(memberId);
            boursoSyncService.resyncIfSessionActive(memberId);

            try {
                cryptoExchangeSyncService.resyncAll(memberId);
            } catch (Exception ex) {
                log.error("Daily crypto exchange sync failed for member {}", memberId, ex);
            }

            try {
                WalletSyncService.ResyncSummary walletSummary = walletSyncService.resyncAll(memberId);
                if (!walletSummary.failed().isEmpty()) {
                    log.warn("Daily wallet sync for member {}: {}/{} succeeded, failed chains: {}",
                        memberId, walletSummary.succeeded(), walletSummary.total(), walletSummary.failed());
                }
            } catch (Exception ex) {
                log.error("Daily wallet sync failed for member {}", memberId, ex);
            }

            try {
                FinaryAutoSyncResponse finaryResult = finaryApiSyncService.autoSync(memberId);
                if ("NEEDS_MAPPING".equals(finaryResult.status())) {
                    log.info("Finary auto-sync for member {} found new accounts, manual mapping required", memberId);
                } else if ("OK".equals(finaryResult.status())) {
                    log.info("Finary auto-sync completed for member {}: {} accounts synced", memberId, finaryResult.accountsSynced());
                } else if ("TOTP_REQUIRED".equals(finaryResult.status())) {
                    log.warn("Finary auto-sync for member {} requires TOTP — user must re-authenticate", memberId);
                }
            } catch (Exception ex) {
                log.error("Daily Finary auto-sync failed for member {}", memberId, ex);
            }
        }
    }

    /**
     * Daily at 08:05: Take a balance snapshot for all accounts.
     */
    @Scheduled(cron = "0 5 8 * * *")
    @Transactional
    public void dailySnapshots() {
        log.info("Taking daily snapshots for all accounts");
        LocalDate today = LocalDate.now();
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            List<Account> memberAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());

            for (Account account : memberAccounts) {
                Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), today);
                if (existing.isEmpty()) {
                    BigDecimal balance = accountService.liveBalanceEur(account);
                    BigDecimal invested = accountService.calculateInvestedAmount(account);
                    snapshotRepository.save(BalanceSnapshot.builder()
                        .account(account)
                        .date(today)
                        .balance(balance)
                        .investedAmount(invested)
                        .build());
                }
            }
        }

        log.debug("Snapshots taken for all members");
    }

    /**
     * Every hour: Refresh prices for accounts with tickers.
     */
    @Scheduled(fixedDelay = 3600000)
    public void refreshPrices() {
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            Set<String> tickers = accountRepository.findByTickerIsNotNullAndMemberId(member.getId())
                .stream()
                .map(Account::getTicker)
                .collect(Collectors.toSet());

            if (!tickers.isEmpty()) {
                log.debug("Refreshing prices for member {} tickers: {}", member.getId(), tickers);
                priceService.refreshPrices(tickers);
            }
        }
    }
}
