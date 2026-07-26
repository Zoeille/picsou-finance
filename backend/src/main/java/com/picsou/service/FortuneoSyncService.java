package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FortuneoSession;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.port.FortuneoPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class FortuneoSyncService {
    private static final Logger log = LoggerFactory.getLogger(FortuneoSyncService.class);
    private static final String PROVIDER = "Fortuneo";
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");
    private static final Set<AccountType> INVESTMENT_TYPES = Set.of(AccountType.PEA, AccountType.COMPTE_TITRES);
    private static final Set<AccountType> CASH_TYPES = Set.of(AccountType.CHECKING, AccountType.SAVINGS);
    private static final int TRANSACTION_WINDOW_DAYS = 90;

    private final FortuneoPort port;
    private final FortuneoSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public FortuneoSyncService(
        FortuneoPort port,
        FortuneoSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("fortuneoSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.isinConverter = isinConverter;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        FortuneoPort.InitiateResult result = port.initiateAuth(login, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            FortuneoSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    FortuneoErrorCode.SESSION_EXPIRED,
                    "No active Fortuneo session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    FortuneoErrorCode.SESSION_EXPIRED,
                    "The Fortuneo session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == FortuneoSyncStatus.QUEUED
                || session.getSyncStatus() == FortuneoSyncStatus.RUNNING) {
                return new QueueDecision(null, toStatus(session));
            }

            String plainState = encryption.decrypt(session.getSessionState());
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(
                new SyncJob(session.getId(), memberId, plainState),
                toStatus(session)
            );
        }));

        if (decision.job() != null) {
            submit(decision.job());
            return getStatus(memberId);
        }
        return decision.status();
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId) {
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            FortuneoSession newSession = FortuneoSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            FortuneoSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, FortuneoErrorCode.INTERNAL_ERROR);
            throw error(
                FortuneoErrorCode.INTERNAL_ERROR,
                "Could not schedule the Fortuneo synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<FortuneoPort.AccountData> fetched = port.fetchAccounts(job.plainState());
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            if (commitPortfolio(job, prepared)) {
                log.info("Fortuneo sync completed (member={}; accounts={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale Fortuneo sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            FortuneoErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Fortuneo sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, FortuneoErrorCode.INTERNAL_ERROR);
            log.error("Fortuneo sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Fortuneo sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            FortuneoSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != FortuneoSyncStatus.QUEUED) {
                log.warn(
                    "Fortuneo sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(),
                    job.memberId(),
                    session.isActive()
                );
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    private List<PreparedAccount> prepareAccounts(List<FortuneoPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                "Fortuneo returned no complete portfolio accounts",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (FortuneoPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                    "Fortuneo returned an incomplete portfolio",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned duplicate accounts", null);
            }
            if (!INVESTMENT_TYPES.contains(account.type()) && !CASH_TYPES.contains(account.type())) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.cashBalance() == null || account.positions() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned incomplete account values", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal investedAmount;
            if (CASH_TYPES.contains(account.type())) {
                // Cash accounts carry no positions: the whole balance is cash, and there is
                // nothing to reconcile against a sum of position valuations.
                if (!positions.isEmpty()) {
                    throw error(
                        FortuneoErrorCode.INVALID_DATA,
                        "Fortuneo returned positions for a cash account",
                        null
                    );
                }
                if (!moneyClose(account.cashBalance(), account.balanceEur())) {
                    throw error(
                        FortuneoErrorCode.INVALID_DATA,
                        "Fortuneo returned a cash account whose balance does not match its cash",
                        null
                    );
                }
                investedAmount = account.balanceEur();
            } else {
                BigDecimal positionValue = positions.stream()
                    .map(PreparedPosition::currentValueEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal expectedPositionValue = account.balanceEur().subtract(account.cashBalance());
                if (!moneyClose(positionValue, expectedPositionValue)) {
                    throw error(
                        FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                        "Fortuneo returned an incomplete portfolio",
                        null
                    );
                }
                investedAmount = investedAmount(account, positions);
            }

            List<FortuneoPort.Transaction> transactions = prepareTransactions(account.transactions());
            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "Fortuneo account"),
                account.type(),
                account.balanceEur(),
                account.cashBalance(),
                investedAmount,
                positions,
                transactions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<FortuneoPort.Transaction> prepareTransactions(List<FortuneoPort.Transaction> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        for (FortuneoPort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amount() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an incomplete transaction", null);
            }
        }
        return List.copyOf(raw);
    }

    private List<PreparedPosition> preparePositions(List<FortuneoPort.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (FortuneoPort.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    FortuneoErrorCode.INVALID_DATA,
                    "Fortuneo returned a quote without its currency",
                    null
                );
            }
            String ticker = resolveTicker(position);
            PreparedPosition resolved = new PreparedPosition(
                ticker,
                limit(position.label(), 100, ticker),
                position.quantity(),
                position.buyingPriceEur(),
                position.currentPrice(),
                currency,
                position.currentValueEur(),
                position.pnlEur()
            );
            positions.merge(ticker, resolved, this::mergePositions);
        }
        return positions.values().stream()
            .filter(position -> position.quantity().signum() != 0)
            .toList();
    }

    private String resolveTicker(FortuneoPort.Position position) {
        String ticker = clean(position.symbol());
        String isin = normalizeIsin(position.isin());
        if (isin != null) {
            OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
            if (resolved != null && resolved.ticker() != null && !resolved.ticker().isBlank()) {
                ticker = resolved.ticker().trim();
            }
        }
        if (ticker == null || ticker.length() > 30) {
            if (isin != null && isin.length() <= 30) {
                ticker = isin;
            }
        }
        if (ticker == null || ticker.length() > 30) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid instrument identifier", null);
        }
        return ticker;
    }

    private String normalizeIsin(String raw) {
        String isin = clean(raw);
        if (isin == null) {
            return null;
        }
        isin = isin.toUpperCase(java.util.Locale.ROOT);
        if (!isin.matches("[A-Z]{2}[A-Z0-9]{10}")) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid ISIN", null);
        }
        return isin;
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned conflicting quote currencies", null);
        }
        BigDecimal quantity = left.quantity().add(right.quantity());
        return new PreparedPosition(
            left.ticker(),
            right.name() != null ? right.name() : left.name(),
            quantity,
            weightedAverage(left.averageBuyInEur(), left.quantity(), right.averageBuyInEur(), right.quantity(), quantity),
            weightedAverage(left.currentPrice(), left.quantity(), right.currentPrice(), right.quantity(), quantity),
            left.quoteCurrency(),
            left.currentValueEur().add(right.currentValueEur()),
            sumComplete(left.pnlEur(), right.pnlEur())
        );
    }

    private BigDecimal weightedAverage(
        BigDecimal left,
        BigDecimal leftQuantity,
        BigDecimal right,
        BigDecimal rightQuantity,
        BigDecimal totalQuantity
    ) {
        if (left == null || right == null || totalQuantity.signum() == 0) {
            return null;
        }
        return left.multiply(leftQuantity)
            .add(right.multiply(rightQuantity))
            .divide(totalQuantity, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal sumComplete(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    private BigDecimal investedAmount(
        FortuneoPort.AccountData account,
        List<PreparedPosition> positions
    ) {
        BigDecimal invested = account.cashBalance();
        for (PreparedPosition position : positions) {
            BigDecimal costBasis = null;
            if (position.averageBuyInEur() != null) {
                costBasis = position.averageBuyInEur().multiply(position.quantity());
            } else if (position.pnlEur() != null) {
                costBasis = position.currentValueEur().subtract(position.pnlEur());
            }
            if (costBasis == null) {
                return account.balanceEur();
            }
            invested = invested.add(costBasis);
        }
        return invested;
    }

    private boolean commitPortfolio(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Fortuneo sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            FortuneoSession session = current.get();
            if (!session.isActive()) {
                log.warn("Fortuneo sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != FortuneoSyncStatus.RUNNING) {
                log.warn(
                    "Fortuneo sync cannot commit from state {} (member={})",
                    session.getSyncStatus(),
                    job.memberId()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("Fortuneo skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(colorFor(data.type()))
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        account.setCashBalance(data.cashBalance());
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);

        holdingRepository.deleteByAccountId(savedAccount.getId());
        holdingRepository.flush();
        List<AccountHolding> holdings = data.positions().stream()
            .map(position -> AccountHolding.builder()
                .account(savedAccount)
                .ticker(position.ticker())
                .name(position.name())
                .quantity(position.quantity())
                .averageBuyIn(position.averageBuyInEur())
                .currentPrice(position.currentPrice())
                .quoteCurrency(position.quoteCurrency())
                .providerValueEur(position.currentValueEur())
                .providerPnlEur(position.pnlEur())
                .lastSyncedAt(syncedAt)
                .build())
            .toList();
        holdingRepository.saveAll(holdings);
        holdingRepository.flush();

        replaceRecentTransactions(savedAccount, data.transactions());

        accountService.upsertSnapshot(
            savedAccount,
            data.balanceEur(),
            data.investedAmountEur(),
            LocalDate.now()
        );
    }

    /**
     * Replaces the trailing {@link #TRANSACTION_WINDOW_DAYS}-day window of non-manual
     * transactions on every sync, keeping older history and any user-entered rows intact.
     * Mirrors BoursoSyncService's transaction replacement strategy.
     */
    private void replaceRecentTransactions(Account account, List<FortuneoPort.Transaction> transactions) {
        if (transactions.isEmpty()) {
            // Deliberately leaves existing rows alone rather than clearing the window:
            // an empty response is far more likely to mean "nothing fetched" than
            // "every recent transaction was reversed", and wiping real history on a
            // thin response is not recoverable from here.
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(TRANSACTION_WINDOW_DAYS);
        // Delete only inside the window. Deleting every non-manual row and re-saving
        // the older ones (BoursoSyncService's approach) re-saves managed entities whose
        // rows have just been deleted, which merges onto a missing row and throws
        // StaleObjectStateException -- confirmed live, as soon as the account had any
        // history older than the window to keep.
        transactionRepository.deleteByAccountIdAndIsManualFalseAndDateGreaterThanEqual(
            account.getId(), cutoff);
        transactionRepository.flush();

        // Insert only what was deleted. The provider returns more history than the
        // window, and inserting it all while deleting only inside the window appends a
        // fresh copy of every older row on each sync -- confirmed live, three copies
        // deep, before this filter existed.
        List<Transaction> toInsert = transactions.stream()
            .filter(tx -> !tx.date().isBefore(cutoff))
            .map(tx -> Transaction.builder()
                .account(account)
                .date(tx.date())
                .description(limit(tx.label(), 255, "Fortuneo transaction"))
                .amount(tx.amount())
                .category(tx.category())
                .nativeCurrency("EUR")
                .build())
            .toList();
        transactionRepository.saveAll(toInsert);
    }

    private String colorFor(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case CHECKING -> "#f59e0b";
            case SAVINGS -> "#8b5cf6";
            default -> "#6b7280";
        };
    }

    private void markFailed(SyncJob job, FortuneoErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("Fortuneo sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                FortuneoSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "Fortuneo sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(),
                        job.memberId(),
                        code
                    );
                    return;
                }
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error(
                "Could not persist Fortuneo sync failure (member={}; code={})",
                job.memberId(),
                code,
                ex
            );
        }
    }

    /**
     * Browser-backed jobs cannot survive a backend restart. Turn persisted
     * in-flight states into a retryable failure instead of leaving the UI
     * polling QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(FortuneoSyncStatus.QUEUED, FortuneoSyncStatus.RUNNING),
            FortuneoSyncStatus.FAILED,
            Instant.now(),
            FortuneoErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Fortuneo sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    public void clearSession(Long memberId) {
        txTemplate.executeWithoutResult(status ->
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete)
        );
    }

    public void resyncIfSessionActive(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                return;
            }
            queueSync(memberId);
        } catch (ResourceNotFoundException ex) {
            log.debug("Member disappeared before scheduled Fortuneo sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled Fortuneo sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled Fortuneo sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled Fortuneo sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(FortuneoSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            null,
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private FortuneoErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return FortuneoErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return FortuneoErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return FortuneoErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(FortuneoErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith("ft_") ? cleaned : "ft_" + cleaned;
        if (externalId.length() > 100) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid account identifier", null);
        }
        return externalId;
    }

    private String normalizeCurrency(String raw) {
        String currency = clean(raw);
        if (currency == null) {
            return null;
        }
        currency = currency.toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid quote currency", null);
        }
        return currency;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String limit(String value, int maxLength, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null) {
            cleaned = fallback;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private boolean moneyClose(BigDecimal actual, BigDecimal expected) {
        BigDecimal tolerance = ABSOLUTE_RECONCILIATION_TOLERANCE.max(
            expected.abs().multiply(RELATIVE_RECONCILIATION_TOLERANCE)
        );
        return actual.subtract(expected).abs().compareTo(tolerance) <= 0;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        Instant expiresAt,
        FortuneoSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        FortuneoErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, null, FortuneoSyncStatus.IDLE, null, null, null);
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal cashBalance,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions,
        List<FortuneoPort.Transaction> transactions
    ) {}
    private record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyInEur,
        BigDecimal currentPrice,
        String quoteCurrency,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}
}
