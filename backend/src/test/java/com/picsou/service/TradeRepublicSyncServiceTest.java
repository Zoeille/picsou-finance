package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeRepublicSyncServiceTest {

    @Mock TradeRepublicPort trPort;
    @Mock TradeRepublicSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;

    @InjectMocks TradeRepublicSyncService service;

    /**
     * When two ISINs resolve to the same ticker, the saved holding's averageBuyIn
     * must be the VWAP -- not whichever position HashMap iteration happens to yield first.
     *
     * Scenario: ISIN_A (qty=2, avg=10) and ISIN_B (qty=3, avg=20) both resolve to "RKLB".
     * Expected merged holding: quantity=5, averageBuyIn = (2*10 + 3*20)/5 = 16.
     */
    @Test
    void sync_mergesDuplicateTickersWithVwap() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition pos1 = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition pos2 = new TrPosition("IE00ISIN_B", bd("3"), bd("20"), bd("110"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("1000"), List.of(pos1, pos2));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        when(isinConverter.resolve("IE00ISIN_A")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));
        when(isinConverter.resolve("IE00ISIN_B")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("1000")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getTicker()).isEqualTo("RKLB");
        assertThat(saved.getQuantity()).isEqualByComparingTo("5");
        // VWAP: (2*10 + 3*20) / 5 = 16  -- scale-8 representation 16.00000000
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("16.00000000");
    }

    @Test
    void sync_deletesOldHoldingsWhenPortfolioReturnsEmpty() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        Account existingAccount = Account.builder()
            .id(42L)
            .member(member)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(bd("1000"))
            .externalAccountId("tr_cto")
            .isManual(false)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("0")));

        service.sync(memberId);

        verify(holdingRepository).deleteByAccountId(42L);
        verify(holdingRepository).flush();
        verify(holdingRepository, never()).save(any(AccountHolding.class));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
