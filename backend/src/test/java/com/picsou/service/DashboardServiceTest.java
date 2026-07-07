package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock GoalService goalService;
    @Mock GoalRepository goalRepository;
    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HistoryService historyService;

    @InjectMocks DashboardService dashboardService;

    @Test
    void getDashboard_skipsHoldingCurrentPrice_whenPriceServiceHasNoPrice() {
        Account account = holdingAccount();
        AccountHolding holding = AccountHolding.builder()
            .account(account)
            .ticker("PHYMF")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            .currentPrice(new BigDecimal("999"))
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(account));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        when(priceService.getPriceEur("PHYMF")).thenReturn(null);
        when(historyService.buildHistory(List.of(1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        assertThat(response.totalNetWorth()).isEqualByComparingTo("0");
        assertThat(response.distribution()).hasSize(1);
        assertThat(response.distribution().getFirst().balanceEur()).isEqualByComparingTo("0");
    }

    @Test
    void getDashboard_usesTrustedEurPrice_whenAvailable() {
        Account account = holdingAccount();
        AccountHolding holding = AccountHolding.builder()
            .account(account)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            .currentPrice(new BigDecimal("999"))
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(account));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("200"));
        when(historyService.buildHistory(List.of(1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        assertThat(response.totalNetWorth()).isEqualByComparingTo("2000");
        assertThat(response.distribution()).hasSize(1);
        assertThat(response.distribution().getFirst().balanceEur()).isEqualByComparingTo("2000");
    }

    private Account holdingAccount() {
        return Account.builder()
            .id(1L)
            .name("Brokerage")
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();
    }
}
