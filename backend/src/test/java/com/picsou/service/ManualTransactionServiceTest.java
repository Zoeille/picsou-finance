package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualTransactionServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock HoldingComputeService holdingComputeService;
    @Mock FinaryPersistenceHelper finaryPersistenceHelper;
    @Mock CategoryRepository categoryRepository;
    @Mock CategorizationService categorizationService;
    @Mock OpenFigiIsinConverter openFigiIsinConverter;

    @InjectMocks ManualTransactionService manualTransactionService;

    /** A manual cash account — its balance IS the sum of its (hand-entered) transactions. */
    private Account checkingAccount() {
        return Account.builder()
            .id(1L)
            .name("Main Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .isManual(true)
            .build();
    }

    /** A synced cash account — its balance is owned by the connector, not the transaction ledger. */
    private Account syncedCheckingAccount() {
        return Account.builder()
            .id(3L)
            .name("TR Cash")
            .type(AccountType.CHECKING)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(new BigDecimal("250"))
            .isManual(false)
            .build();
    }

    private Account peaAccount() {
        return Account.builder()
            .id(2L)
            .name("PEA")
            .type(AccountType.PEA)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
    }

    @Test
    void addTransaction_cashAccount_recomputesBalance() {
        Account account = checkingAccount();
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2024, 1, 15),
            "Salary",
            new BigDecimal("1000"),
            TransactionType.DEPOSIT,
            null, null, null, null, "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(1L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.sumAmountByAccountId(1L)).thenReturn(new BigDecimal("1000"));

        TransactionResponse result = manualTransactionService.addTransaction(1L, 10L, req);

        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualByComparingTo("1000");
        verify(transactionRepository).sumAmountByAccountId(1L);
        verify(accountRepository).save(account);
        verify(holdingComputeService, never()).recomputeHoldings(any());
        verify(finaryPersistenceHelper).reconstructSnapshotsFromDb(account);
    }

    @Test
    void addTransaction_syncedCashAccount_leavesSyncOwnedBalanceUntouched() {
        Account account = syncedCheckingAccount();
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2024, 1, 15),
            "Manual annotation",
            new BigDecimal("-5"),
            TransactionType.WITHDRAWAL,
            null, null, null, null, "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(3L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        manualTransactionService.addTransaction(3L, 10L, req);

        // The annotation is recorded, but the connector-owned balance & history are left alone —
        // recomputing them from a partial ledger would corrupt the balance (the #2 collision).
        verify(transactionRepository).save(any(Transaction.class));
        verify(transactionRepository, never()).sumAmountByAccountId(any());
        verify(accountRepository, never()).save(any());
        verify(finaryPersistenceHelper, never()).reconstructSnapshotsFromDb(any());
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("250");
    }

    @Test
    void addTransaction_investmentAccount_recomputesHoldings() {
        Account account = peaAccount();
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2024, 3, 10),
            "Buy AAPL",
            new BigDecimal("500"),
            TransactionType.BUY,
            "AAPL",
            null,
            new BigDecimal("5"),
            new BigDecimal("100"),
            "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse result = manualTransactionService.addTransaction(2L, 10L, req);

        assertThat(result).isNotNull();
        assertThat(result.ticker()).isEqualTo("AAPL");
        verify(holdingComputeService).recomputeHoldings(account);
        verify(finaryPersistenceHelper, never()).reconstructSnapshotsFromDb(any());
        verify(transactionRepository, never()).sumAmountByAccountId(any());
    }

    @Test
    void addTransaction_accountNotFound_throws() {
        TransactionRequest req = new TransactionRequest(
            LocalDate.now(), "Test", BigDecimal.TEN,
            TransactionType.DEPOSIT, null, null, null, null, "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(99L, 10L)).thenReturn(Optional.empty());

        // The user-facing message must NOT leak the resource id (Account 99) — it
        // is intentionally ID-free now, so assert on the friendly text instead.
        assertThatThrownBy(() -> manualTransactionService.addTransaction(99L, 10L, req))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Account not found")
            .hasMessageNotContaining("99");
    }

    @Test
    void deleteTransaction_manual_deletesAndRecomputes() {
        Account account = checkingAccount();
        Transaction tx = Transaction.builder()
            .id(5L)
            .account(account)
            .date(LocalDate.of(2024, 1, 10))
            .description("Manual entry")
            .amount(new BigDecimal("200"))
            .isManual(true)
            .nativeCurrency("EUR")
            .build();

        when(accountRepository.findByIdAndMemberId(1L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByIdAndAccountId(5L, 1L)).thenReturn(Optional.of(tx));
        when(transactionRepository.sumAmountByAccountId(1L)).thenReturn(BigDecimal.ZERO);

        manualTransactionService.deleteTransaction(1L, 5L, 10L);

        verify(transactionRepository).delete(tx);
        verify(transactionRepository).sumAmountByAccountId(1L);
        verify(accountRepository).save(account);
        verify(finaryPersistenceHelper).reconstructSnapshotsFromDb(account);
        verify(holdingComputeService, never()).recomputeHoldings(any());
    }

    @Test
    void deleteTransaction_syncedTransaction_throws() {
        Account account = checkingAccount();
        Transaction tx = Transaction.builder()
            .id(6L)
            .account(account)
            .date(LocalDate.of(2024, 2, 1))
            .description("Synced entry")
            .amount(new BigDecimal("300"))
            .isManual(false)
            .nativeCurrency("EUR")
            .build();

        when(accountRepository.findByIdAndMemberId(1L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByIdAndAccountId(6L, 1L)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> manualTransactionService.deleteTransaction(1L, 6L, 10L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("synced");

        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void deleteTransaction_transactionNotFound_throws() {
        Account account = checkingAccount();

        when(accountRepository.findByIdAndMemberId(1L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByIdAndAccountId(99L, 1L)).thenReturn(Optional.empty());

        // ID-free user-facing message (no leaking of transaction id 99).
        assertThatThrownBy(() -> manualTransactionService.deleteTransaction(1L, 99L, 10L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Transaction not found")
            .hasMessageNotContaining("99");
    }

    @Test
    void addTransaction_isinInput_resolvesTickerNameAndDescription() {
        Account account = peaAccount();
        // The frontend puts the ISIN in the ticker field; with Nom blank it sends a placeholder description.
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2024, 3, 10),
            "Achat IE00B4L5Y983",
            new BigDecimal("500"),
            TransactionType.BUY,
            "IE00B4L5Y983",
            null,
            new BigDecimal("5"),
            new BigDecimal("100"),
            "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(openFigiIsinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core MSCI World UCITS ETF"));

        TransactionResponse result = manualTransactionService.addTransaction(2L, 10L, req);

        // ISIN is normalized to the Yahoo ticker so positions merge and pricing works.
        assertThat(result.ticker()).isEqualTo("IWDA.AS");
        // The resolved name labels the position.
        assertThat(result.name()).isEqualTo("iShares Core MSCI World UCITS ETF");
        // The raw ISIN must never surface in the transaction row.
        assertThat(result.description()).isEqualTo("iShares Core MSCI World UCITS ETF");
        verify(holdingComputeService).recomputeHoldings(account);
    }

    @Test
    void addTransaction_plainTicker_uppercasesAndUserNameWins() {
        Account account = peaAccount();
        TransactionRequest req = new TransactionRequest(
            LocalDate.of(2024, 3, 10),
            "ignored placeholder",
            new BigDecimal("500"),
            TransactionType.BUY,
            "iwda.as",
            "My World ETF",
            new BigDecimal("5"),
            new BigDecimal("100"),
            "EUR", null
        );

        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse result = manualTransactionService.addTransaction(2L, 10L, req);

        // A plain ticker is just uppercased — no network resolution.
        assertThat(result.ticker()).isEqualTo("IWDA.AS");
        // A user-supplied Nom always wins over any resolved name.
        assertThat(result.name()).isEqualTo("My World ETF");
        // The row description is the chosen name, not the placeholder.
        assertThat(result.description()).isEqualTo("My World ETF");
        verify(openFigiIsinConverter, never()).resolve(any());
    }
}
