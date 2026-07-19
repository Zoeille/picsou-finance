package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.IbkrConnection;
import com.picsou.port.IbkrFlexPort;
import com.picsou.port.IbkrFlexPort.IbkrAccountData;
import com.picsou.port.IbkrFlexPort.IbkrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.IbkrConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IbkrSyncServiceTest {

    @Mock IbkrFlexPort ibkrFlexPort;
    @Mock IbkrConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;

    @InjectMocks IbkrSyncService service;

    /**
     * IBKR reports cost basis in the security's native currency. The stored
     * {@code averageBuyIn} must be converted to the account base currency (≈EUR) via
     * {@code fxRateToBase}, and per-tax-lot ("LOT") duplicate rows must be dropped so a
     * position is not double-counted.
     *
     * Scenario: 10 AAPL, costBasisPrice=150 USD, fxRateToBase=0.92, plus a LOT duplicate.
     * Expected single holding: quantity=10, averageBuyIn = 150 * 0.92 = 138.
     */
    @Test
    void sync_convertsCostBasisToBaseCurrencyAndDropsLotRows() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        IbkrConnection connection = IbkrConnection.builder()
            .member(member)
            .token("enc-token")
            .queryId("enc-query")
            .status("CONNECTED")
            .build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition summary = new IbkrPosition(
            "U123", "US0378331005", "AAPL", "APPLE INC", "USD", "STK", "SUMMARY",
            bd("10"), bd("200"), bd("150"), bd("0.92"));
        IbkrPosition lotDuplicate = new IbkrPosition(
            "U123", "US0378331005", "AAPL", "APPLE INC", "USD", "STK", "LOT",
            bd("10"), bd("200"), bd("150"), bd("0.92"));
        IbkrAccountData accountData = new IbkrAccountData("U123", List.of(summary, lotDuplicate));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(accountData));

        when(isinConverter.resolve("US0378331005")).thenReturn(new TickerResult("AAPL", "Apple"));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U123", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U123", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(accountService.liveBalanceEur(any(Account.class))).thenReturn(bd("1380"));
        AccountResponse dummy = AccountResponse.from(
            Account.builder().id(1L).name("IBKR U123").type(AccountType.COMPTE_TITRES).build(),
            BigDecimal.ZERO);
        when(accountService.toResponse(any(Account.class))).thenReturn(dummy);

        List<AccountResponse> result = service.sync(memberId);

        assertThat(result).hasSize(1);

        // The LOT row must be filtered → exactly one holding saved.
        ArgumentCaptor<AccountHolding> holdingCaptor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository, times(1)).save(holdingCaptor.capture());
        AccountHolding saved = holdingCaptor.getValue();

        assertThat(saved.getTicker()).isEqualTo("AAPL");
        assertThat(saved.getQuantity()).isEqualByComparingTo("10");
        // 150 USD × 0.92 = 138 EUR — the core currency-conversion contract.
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("138");

        // Connection is marked freshly synced.
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
    }

    @Test
    void sync_withoutConnection_throws() {
        when(connectionRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(99L))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("No Interactive Brokers connection");
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
