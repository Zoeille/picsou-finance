package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BoursoSession;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.BoursoPort;
import com.picsou.port.BoursoPort.BoursoAccountData;
import com.picsou.port.BoursoPort.BoursoPosition;
import com.picsou.port.BoursoPort.BoursoTransaction;
import com.picsou.port.BoursoPort.InitiateResult;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Transactional
public class BoursoSyncService {

    private static final Logger log = LoggerFactory.getLogger(BoursoSyncService.class);

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bourso-sync");
        t.setDaemon(true);
        return t;
    });

    private final BoursoPort               boursoPort;
    private final BoursoSessionRepository  sessionRepository;
    private final AccountRepository        accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository    transactionRepository;
    private final FamilyMemberRepository   familyMemberRepository;
    private final AccountService           accountService;
    private final OpenFigiIsinConverter    isinConverter;
    private final CryptoEncryption         encryption;
    private final TransactionTemplate      txTemplate;

    public BoursoSyncService(
        BoursoPort boursoPort,
        BoursoSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate
    ) {
        this.boursoPort           = boursoPort;
        this.sessionRepository    = sessionRepository;
        this.accountRepository    = accountRepository;
        this.holdingRepository    = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService       = accountService;
        this.isinConverter        = isinConverter;
        this.encryption           = encryption;
        this.txTemplate           = txTemplate;
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    /**
     * Step 1: Authenticates with BoursoBank.
     * - If no MFA required: session is stored immediately and sync fires in background.
     *   Returns AuthInitResponse with mfaRequired=false and session status.
     * - If MFA required: returns processId + MFA details; caller must invoke completeAuth().
     */
    public AuthInitResponse initiateAuth(String customerId, String password, Long memberId) {
        InitiateResult result = boursoPort.initiateAuth(customerId, password);

        if (!result.mfaRequired()) {
            storeSessionAndSync(result.sessionCookies(), memberId);
            return new AuthInitResponse(null, false, null, null);
        }

        return new AuthInitResponse(result.processId(), true, result.mfaType(), result.contact());
    }

    /**
     * Step 2 (MFA only): Exchanges the OTP for session cookies, stores them,
     * and fires background sync.
     */
    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String cookies = boursoPort.completeAuth(processId, code);
        return storeSessionAndSync(cookies, memberId);
    }

    private SessionStatusResponse storeSessionAndSync(String plainCookies, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);

        // BoursoBank sessions typically last several weeks
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

        BoursoSession session = BoursoSession.builder()
            .member(member)
            .sessionCookies(encryption.encrypt(plainCookies))
            .expiresAt(expiresAt)
            .build();
        session = sessionRepository.save(session);

        log.info("BoursoBank session stored for member {}, firing background sync", memberId);

        final Long sessionId = session.getId();
        CompletableFuture.runAsync(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    sessionRepository.findById(sessionId); // ensure entity is loaded in this tx context
                    syncWithCookies(plainCookies, memberId);
                });
                log.info("BoursoBank background sync complete");
            } catch (Exception ex) {
                log.error("BoursoBank background sync failed: {}", ex.getMessage());
            }
        }, syncExecutor);

        return new SessionStatusResponse(true, expiresAt);
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    public List<AccountResponse> sync(Long memberId) {
        BoursoSession stored = sessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No BoursoBank session. Please connect from the BoursoBank page."));
        return syncWithCookies(encryption.decrypt(stored.getSessionCookies()), memberId);
    }

    private List<AccountResponse> syncWithCookies(String plainCookies, Long memberId) {
        try {
            List<BoursoAccountData> accounts = boursoPort.fetchAccounts(plainCookies);
            List<AccountResponse> responses = accounts.stream()
                .map(data -> upsertAccount(data, memberId))
                .toList();
            log.info("BoursoBank sync complete: {} accounts updated", responses.size());
            return responses;
        } catch (SyncException e) {
            if ("SESSION_EXPIRED".equals(e.getMessage())) {
                log.warn("BoursoBank session expired for member {} — clearing session", memberId);
                sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
                throw new SyncException(
                    "Your BoursoBank session has expired. Please reconnect from the BoursoBank page.");
            }
            throw e;
        }
    }

    // ─── Session status ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SessionStatusResponse getSessionStatus(Long memberId) {
        Optional<BoursoSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) return new SessionStatusResponse(false, null);
        BoursoSession s = session.get();
        boolean active = s.getExpiresAt() == null || s.getExpiresAt().isAfter(Instant.now());
        return new SessionStatusResponse(active, s.getExpiresAt());
    }

    public void clearSession(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("BoursoBank session cleared for member {}", memberId);
    }

    // ─── Scheduler entry point ────────────────────────────────────────────────

    public void resyncIfSessionActive(Long memberId) {
        Optional<BoursoSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) return;

        BoursoSession s = session.get();
        if (s.getExpiresAt() != null && !s.getExpiresAt().isAfter(Instant.now())) {
            log.warn("BoursoBank session expired for member {} — skipping auto-sync", memberId);
            return;
        }

        try {
            syncWithCookies(encryption.decrypt(s.getSessionCookies()), memberId);
        } catch (Exception ex) {
            log.warn("BoursoBank auto-sync failed for member {}: {}", memberId, ex.getMessage());
        }
    }

    // ─── Upsert ───────────────────────────────────────────────────────────────

    private AccountResponse upsertAccount(BoursoAccountData data, Long memberId) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balanceEur());
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(data.name())
                .type(data.type())
                .provider("BoursoBank")
                .currency("EUR")
                .currentBalance(data.balanceEur())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color(colorFor(data.type()))
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balanceEur(), LocalDate.now());

        // Holdings
        if (!data.positions().isEmpty()) {
            holdingRepository.deleteByAccountId(account.getId());
            holdingRepository.flush();

            Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
            for (BoursoPosition p : data.positions()) {
                String ticker;
                String name = p.label();
                if (p.isin() != null && !p.isin().isBlank()) {
                    var resolved = isinConverter.resolve(p.isin());
                    ticker = resolved.ticker();
                    if (resolved.name() != null) name = resolved.name();
                } else {
                    ticker = p.symbol();
                }
                deduped.merge(
                    ticker,
                    new HoldingDedup.HoldingAgg(p.quantity(), p.buyingPrice(), p.currentPrice(), name),
                    HoldingDedup::vwapMerge);
            }

            for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
                HoldingDedup.HoldingAgg agg = entry.getValue();
                if (agg.quantity().signum() == 0) {
                    // vwapMerge is sign-aware and can net two positions to exactly zero
                    // (shared with IBKR, which can carry short quantities) -- a flat
                    // position, not a holding to persist. Bourso only ever feeds positive
                    // quantities today, so this is unreachable here but keeps the
                    // invariant true regardless.
                    continue;
                }
                holdingRepository.save(AccountHolding.builder()
                    .account(account)
                    .ticker(entry.getKey())
                    .name(agg.name())
                    .quantity(agg.quantity())
                    .averageBuyIn(agg.averageBuyIn())
                    .currentPrice(agg.currentPrice())
                    .lastSyncedAt(Instant.now())
                    .build());
            }
        }

        // Transactions (replace last 90 days window)
        if (!data.transactions().isEmpty()) {
            LocalDate cutoff = LocalDate.now().minusDays(90);
            List<Transaction> existingTx = transactionRepository.findByAccountIdOrderByDateDesc(account.getId());
            List<Transaction> toKeep     = existingTx.stream()
                .filter(t -> t.getDate().isBefore(cutoff) && !t.isManual())
                .toList();

            transactionRepository.deleteByAccountIdAndIsManualFalse(account.getId());
            transactionRepository.flush();
            transactionRepository.saveAll(toKeep);

            List<Transaction> toInsert = new ArrayList<>();
            for (BoursoTransaction bt : data.transactions()) {
                toInsert.add(Transaction.builder()
                    .account(account)
                    .date(bt.date())
                    .description(bt.label())
                    .amount(bt.amount())
                    .category(bt.category())
                    .nativeCurrency("EUR")
                    .build());
            }
            transactionRepository.saveAll(toInsert);
        }

        return accountService.toResponse(account);
    }

    private String colorFor(AccountType type) {
        return switch (type) {
            case PEA           -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case SAVINGS       -> "#8b5cf6";
            case LEP           -> "#a855f7";
            default            -> "#6366f1";
        };
    }

    // ─── Response records ─────────────────────────────────────────────────────

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType, String contact) {}

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
