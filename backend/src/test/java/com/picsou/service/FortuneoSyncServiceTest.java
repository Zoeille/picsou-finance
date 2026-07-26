package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class FortuneoSyncServiceTest {
    @Mock FortuneoPort port;
    @Mock FortuneoSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;
    @Captor ArgumentCaptor<List<Transaction>> transactionsCaptor;

    FortuneoSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSync_commitsCompletePortfolioThenWritesSnapshotFromCurrentPositions() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Account existingAccount = Account.builder()
            .id(11L)
            .member(member)
            .name("Edited account")
            .type(AccountType.OTHER)
            .provider("Edited provider")
            .currency("USD")
            .currentBalance(BigDecimal.ZERO)
            .isManual(true)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("ft_pea-123", 7L))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            return account;
        });

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getProvider()).isEqualTo("Fortuneo");
        assertThat(savedAccount.getCurrency()).isEqualTo("EUR");
        assertThat(savedAccount.isManual()).isFalse();
        assertThat(savedAccount.getType()).isEqualTo(AccountType.PEA);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("ACME");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("80");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("1000");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("200");

        InOrder writeOrder = inOrder(holdingRepository, accountService);
        writeOrder.verify(holdingRepository).saveAll(any());
        writeOrder.verify(holdingRepository).flush();
        writeOrder.verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1250")),
            eq(new BigDecimal("1050")),
            eq(LocalDate.now())
        );
    }

    @Test
    void cashAccount_syncsBalanceWithoutPositionsOrReconciliation() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of()
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.CHECKING);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).isEmpty();
    }

    @Test
    void cashAccountWithPositions_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position stray = position(
            "FR0000000001", "ACME", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "cc-1", "Compte Courant", AccountType.CHECKING,
            new BigDecimal("500"), new BigDecimal("500"), List.of(stray), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void cashAccountBalanceMismatch_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "cc-1", "Compte Courant", AccountType.CHECKING,
            new BigDecimal("500"), new BigDecimal("400"), List.of(), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void transactions_replaceTheTrailingNinetyDayWindow() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Transaction incoming = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Virement recu", new BigDecimal("100"), "Virement"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(incoming)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        // Only the window is deleted, and nothing is re-saved: older rows are never
        // touched, so they keep their ids and cannot be merged onto deleted rows.
        verify(transactionRepository)
            .deleteByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(20L), any(LocalDate.class));
        verify(transactionRepository).saveAll(transactionsCaptor.capture());
        List<Transaction> inserted = transactionsCaptor.getValue();
        assertThat(inserted).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("Virement recu");
            assertThat(tx.getAmount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void transactions_olderThanTheWindowAreNotReinserted() {
        // Regression: the provider returns more history than the 90-day window. Deleting
        // only inside the window while inserting everything appended a fresh copy of each
        // older row on every sync -- three copies deep in a real database before this.
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Transaction recent = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Dans la fenetre", new BigDecimal("100"), "Virement"
        );
        FortuneoPort.Transaction old = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(200), "Hors fenetre", new BigDecimal("42"), "Virement"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(recent, old)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionRepository).saveAll(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue())
            .singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo("Dans la fenetre"));
    }

    @Test
    void incompleteSnapshot_failsWithoutDeletingExistingHoldings() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.AccountData incomplete = new FortuneoPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(), List.of(), false
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(incomplete));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void nativeQuoteWithoutCurrency_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position ambiguous = new FortuneoPort.Position(
            null, "ACME", "Acme", BigDecimal.ONE, new BigDecimal("80"),
            new BigDecimal("100"), null, new BigDecimal("100"), new BigDecimal("20")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("100"), BigDecimal.ZERO, List.of(ambiguous), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void expiredSession_isInvalidatedInFollowUpTransactionAndRemainsObservable() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(new SyncException(
            "Session expired", null, FortuneoErrorCode.SESSION_EXPIRED.name()
        ));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.isActive()).isFalse();
        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.SESSION_EXPIRED);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void completeAuth_returnsQueuedBeforePortfolioFetchRuns() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        FortuneoSession stored = FortuneoSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.QUEUED)
            .build();
        when(port.completeAuth("process", "123456")).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(FortuneoSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        FortuneoSyncService.SessionStatusResponse result =
            delayedService.completeAuth("process", "123456", 7L);

        assertThat(result.isActive()).isTrue();
        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.QUEUED);
        assertThat(queuedTask.get()).isNotNull();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void initiateAuth_withoutMfaStoresSessionAndQueuesTheInitialSync() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        FortuneoSession stored = FortuneoSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.QUEUED)
            .build();
        when(port.initiateAuth("login", "password")).thenReturn(
            new FortuneoPort.InitiateResult(null, false, null, "plain-state")
        );
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(FortuneoSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        FortuneoSyncService.AuthInitResponse response =
            delayedService.initiateAuth("login", "password", 7L);

        assertThat(response.mfaRequired()).isFalse();
        assertThat(response.processId()).isNull();
        assertThat(queuedTask.get()).isNotNull();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_doesNotScheduleDuplicateWhileJobIsRunning() {
        FortuneoSession session = activeSession(member());
        session.markQueued();
        session.markRunning(Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.RUNNING);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void reconciliationMismatch_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position position = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("1000"),
            new BigDecimal("800"), new BigDecimal("1000"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("1300"), new BigDecimal("250"), List.of(position)
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void unsupportedAccountType_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "other", AccountType.OTHER, BigDecimal.ZERO, BigDecimal.ZERO, List.of()
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void duplicateExternalAccountId_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.AccountData account = account(
            "same", AccountType.PEA, BigDecimal.TEN, BigDecimal.TEN, List.of()
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account, account));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void replacedSession_preventsAnOldJobFromCommittingItsPortfolio() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FortuneoSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");

        delayedService.queueSync(7L);

        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session), Optional.empty());
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        queuedTask.get().run();

        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void applicationStart_marksInterruptedJobsAsRetryableFailures() {
        when(sessionRepository.markInterruptedSyncsFailed(
            any(),
            eq(FortuneoSyncStatus.FAILED),
            any(),
            eq(FortuneoErrorCode.INTERNAL_ERROR)
        )).thenReturn(2);

        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(FortuneoSyncStatus.QUEUED, FortuneoSyncStatus.RUNNING)),
            eq(FortuneoSyncStatus.FAILED),
            any(),
            eq(FortuneoErrorCode.INTERNAL_ERROR)
        );
    }

    private void arrangeQueuedSession(FortuneoSession session) {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private FortuneoSession activeSession(FamilyMember member) {
        return FortuneoSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.IDLE)
            .build();
    }

    private FortuneoPort.AccountData completeAccount() {
        FortuneoPort.Position position = new FortuneoPort.Position(
            null, "ACME", "Acme SA", new BigDecimal("10"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR",
            new BigDecimal("1000"), new BigDecimal("200")
        );
        return new FortuneoPort.AccountData(
            "pea-123", "PEA Fortuneo", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(position), List.of(), true
        );
    }

    private FortuneoPort.Position position(
        String isin,
        String symbol,
        BigDecimal quantity,
        BigDecimal currentValueEur,
        BigDecimal buyingPriceEur,
        BigDecimal currentPrice,
        String quoteCurrency
    ) {
        return new FortuneoPort.Position(
            isin,
            symbol,
            symbol == null ? "Unknown" : symbol,
            quantity,
            buyingPriceEur,
            currentPrice,
            quoteCurrency,
            currentValueEur,
            null
        );
    }

    private FortuneoPort.AccountData account(
        String externalId,
        AccountType type,
        BigDecimal balance,
        BigDecimal cash,
        List<FortuneoPort.Position> positions
    ) {
        return new FortuneoPort.AccountData(
            externalId,
            type == AccountType.PEA ? "PEA" : "CTO",
            type,
            balance,
            cash,
            positions,
            List.of(),
            true
        );
    }

    private FortuneoPort.AccountData cashAccount(
        String externalId,
        AccountType type,
        BigDecimal balance,
        List<FortuneoPort.Transaction> transactions
    ) {
        return new FortuneoPort.AccountData(
            externalId,
            type == AccountType.CHECKING ? "Compte Courant" : "Livret",
            type,
            balance,
            balance,
            List.of(),
            transactions,
            true
        );
    }

    private void arrangeNewAccountPersistence(Long id) {
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(id);
            return account;
        });
    }

    private FortuneoSyncService serviceWith(Executor executor) {
        return new FortuneoSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            transactionRepository,
            memberRepository,
            accountService,
            isinConverter,
            encryption,
            txTemplate,
            executor
        );
    }

    private void executeTransactionsImmediately() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }
}
