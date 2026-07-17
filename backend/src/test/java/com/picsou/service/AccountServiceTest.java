package com.picsou.service;

import com.picsou.dto.DebtRequest;
import com.picsou.dto.HoldingResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RealEstateMetadataRepository realEstateMetadataRepository;
    @Mock DebtRepository debtRepository;
    @Mock PriceService priceService;
    @Mock LoanAmortizationService loanAmortizationService;
    @InjectMocks AccountService accountService;

    private Account ownedAccount() {
        return Account.builder()
            .id(1L)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .build();
    }

    @Test
    void pruneHoldings_deletesOnlyTickersNotKept() {
        accountService.pruneHoldings(ownedAccount(), Set.of("BTC", "ETH"));

        verify(holdingRepository).deleteByAccountIdAndTickerNotIn(1L, Set.of("BTC", "ETH"));
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void pruneHoldings_emptyKeepSet_clearsAllHoldings() {
        // No asset survived (empty wallet) -> remove every holding, but never issue
        // a NOT IN () against an empty set.
        accountService.pruneHoldings(ownedAccount(), Set.of());

        verify(holdingRepository).deleteByAccountId(1L);
        verify(holdingRepository, never()).deleteByAccountIdAndTickerNotIn(any(), any());
    }

    @Test
    void getHoldings_returnsNullValue_whenPriceServiceHasNoPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("PHYMF")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            // Stored from a broker sync in unknown currency — must NOT be used as EUR.
            .currentPrice(new BigDecimal("999"))
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        when(priceService.getPriceEur("PHYMF")).thenReturn(null);

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        assertThat(result).hasSize(1);
        HoldingResponse h = result.get(0);
        // The key invariant: no fallback to holding.currentPrice (999) × quantity (10) = 9990.
        assertThat(h.currentValueEur()).isNull();
        assertThat(h.pnlEur()).isNull();
        assertThat(h.pnlPercent()).isNull();
    }

    @Test
    void getHoldings_computesValue_whenPriceServiceHasPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("AAPL")
            .quantity(new BigDecimal("5"))
            .averageBuyIn(new BigDecimal("150"))
            .currentPrice(new BigDecimal("180"))  // native-currency, must be ignored
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        // Yahoo returned 200 EUR/share after FX conversion (e.g. ~217 USD × 0.92).
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("200"));

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        HoldingResponse h = result.get(0);
        assertThat(h.currentValueEur()).isEqualByComparingTo("1000"); // 5 × 200
        assertThat(h.pnlEur()).isEqualByComparingTo("250"); // 1000 − (5 × 150)
        assertThat(h.pnlPercent().doubleValue()).isCloseTo(33.33, within(0.1));
    }

    @Test
    void updateDebtMetadata_rejectsLinkedAccount_notOwnedByMember() {
        // Caller (member 1) owns the loan account (id 1)...
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());
        // ...but points linkedAccountId at account 2, which is NOT theirs: the member-scoped
        // lookup finds nothing. Previously this used an unscoped findById, leaking the foreign
        // account's name back via DebtResponse (BOLA). It must now be rejected.
        when(accountRepository.findByIdAndMemberId(2L, 1L)).thenReturn(Optional.empty());

        DebtRequest req = new DebtRequest(
            2L, new BigDecimal("100000"), new BigDecimal("0.03"), new BigDecimal("500"),
            "Bank", null, null, null, null);

        assertThatThrownBy(() -> accountService.updateDebtMetadata(1L, 1L, req))
            .isInstanceOf(ResourceNotFoundException.class);
        // The cross-member reference must never be persisted.
        verify(debtRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ─── liveBalanceEur characterization ──────────────────────────────────────

    private Account loanAccount() {
        return Account.builder()
            .id(1L)
            .name("Mortgage")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("12000"))
            .build();
    }

    @Test
    void liveBalanceEur_loanWithDebt_returnsPositiveRemainingBalance() {
        Account loan = loanAccount();
        Debt debt = Debt.builder().build();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("8500"));

        BigDecimal result = accountService.liveBalanceEur(loan);

        // computeRemainingBalance returns a POSITIVE outstanding amount and
        // liveBalanceEur passes it through unnegated — callers negate loans themselves.
        assertThat(result).isEqualByComparingTo("8500");
    }

    @Test
    void liveBalanceEur_loanWithoutDebt_fallsBackToStoredBalance() {
        Account loan = loanAccount();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());
        when(priceService.toEur(new BigDecimal("12000"), "EUR", null)).thenReturn(new BigDecimal("12000"));

        BigDecimal result = accountService.liveBalanceEur(loan);

        // No Debt row → plain toEur pass-through of the stored balance, sign untouched.
        assertThat(result).isEqualByComparingTo("12000");
    }

    @Test
    void liveBalanceEur_skipsHoldingsWithoutLivePrice() {
        Account account = ownedAccount();
        AccountHolding priced = AccountHolding.builder()
            .ticker("AAPL").quantity(new BigDecimal("5")).build();
        AccountHolding unpriced = AccountHolding.builder()
            .ticker("PHYMF").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(priced, unpriced));
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("200"));
        when(priceService.getPriceEur("PHYMF")).thenReturn(null);

        BigDecimal result = accountService.liveBalanceEur(account);

        // CHARACTERIZATION: unpriced holdings are silently skipped (audit TEST-04).
        assertThat(result).isEqualByComparingTo("1000"); // 5 × 200; PHYMF contributes nothing
    }

    @Test
    void liveBalanceEur_cashAccount_convertsStoredBalance() {
        Account cash = Account.builder()
            .id(2L)
            .name("USD Cash")
            .type(AccountType.CHECKING)
            .currency("USD")
            .currentBalance(new BigDecimal("2500"))
            .build();
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2500"), "USD", null)).thenReturn(new BigDecimal("2300"));

        BigDecimal result = accountService.liveBalanceEur(cash);

        assertThat(result).isEqualByComparingTo("2300");
    }

    // ─── signedLiveBalanceEur ─────────────────────────────────────────────────

    @Test
    void signedLiveBalanceEur_loan_returnsNegativeOutstanding() {
        Account loan = loanAccount();
        Debt debt = Debt.builder().build();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("8500"));

        BigDecimal result = accountService.signedLiveBalanceEur(loan);

        // LOAN accounts are stored positive; the signed helper applies the liability sign.
        assertThat(result).isEqualByComparingTo("-8500");
    }

    @Test
    void signedLiveBalanceEur_checking_returnsBalanceUnchanged() {
        Account cash = Account.builder()
            .id(2L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("2500"))
            .build();
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2500"), "EUR", null)).thenReturn(new BigDecimal("2500"));

        BigDecimal result = accountService.signedLiveBalanceEur(cash);

        assertThat(result).isEqualByComparingTo("2500");
    }
}
