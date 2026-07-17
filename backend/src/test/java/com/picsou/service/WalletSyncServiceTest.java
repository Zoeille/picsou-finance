package com.picsou.service;

import com.picsou.exception.SyncException;
import com.picsou.exception.WalletRpcException;
import com.picsou.model.Account;
import com.picsou.model.Chain;
import com.picsou.model.FamilyMember;
import com.picsou.model.WalletAddress;
import com.picsou.port.WalletPort;
import com.picsou.port.WalletPort.WalletBalance;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WalletAddressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletSyncServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock WalletAddressRepository walletRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;

    private WalletSyncService serviceWith(WalletPort... adapters) {
        return new WalletSyncService(
            List.of(adapters), walletRepository, accountRepository,
            familyMemberRepository, accountService, priceService);
    }

    private static WalletAddress wallet(Long id, Chain chain, String address) {
        return WalletAddress.builder().id(id).chain(chain).address(address).build();
    }

    @Test
    void sync_wrapsRpcErrorInSyncException_andDoesNotMarkWalletSynced() {
        WalletAddress wallet = wallet(1L, Chain.ETHEREUM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("ETHEREUM");
        when(adapter.fetchBalances(any())).thenThrow(new WalletRpcException("Ethereum eth_getBalance: RPC error"));

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasCauseInstanceOf(WalletRpcException.class);

        // Failure surfaced before the wallet was persisted as synced.
        verify(walletRepository, never()).save(any());
    }

    @Test
    void sync_persistsHoldingsAndSnapshot_onSuccess() {
        WalletAddress wallet = wallet(1L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("SOLANA");
        when(adapter.fetchBalances(any())).thenReturn(List.of(
            new WalletBalance("SOL", BigDecimal.ONE),
            new WalletBalance("USDC", new BigDecimal("50"))));

        // 1 SOL @ 20 EUR + 50 USDC @ 1 EUR = 70.00 EUR.
        when(priceService.refreshPrices(any()))
            .thenReturn(Map.of("SOL", new BigDecimal("20"), "USDC", new BigDecimal("1")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        WalletSyncService service = serviceWith(adapter);

        service.sync(1L, MEMBER_ID);

        // The response is built from the resolved account.
        verify(accountService).toResponse(savedAccount);

        // One holding per priced, positive balance.
        verify(accountService, times(2)).upsertHolding(eq(100L), eq(MEMBER_ID), any(), any(), any(), any());

        // Snapshot balance is the summed EUR value -- guards the conversion math.
        ArgumentCaptor<BigDecimal> balanceEur = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountService).upsertSnapshot(eq(savedAccount), balanceEur.capture(), any());
        assertThat(balanceEur.getValue()).isEqualByComparingTo("70.00");

        // lastSyncedAt was stamped and the wallet persisted.
        verify(walletRepository).save(wallet);
        assertThat(wallet.getLastSyncedAt()).isNotNull();
    }

    @Test
    void sync_throwsSyncException_whenAdapterReturnsNoBalances() {
        WalletAddress wallet = wallet(1L, Chain.ETHEREUM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("ETHEREUM");
        when(adapter.fetchBalances(any())).thenReturn(List.of());

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class);
    }

    @Test
    void resyncAll_reportsFailedChain_andKeepsSyncingOthers() {
        WalletAddress eth = wallet(1L, Chain.ETHEREUM, "0xabc");
        WalletAddress sol = wallet(2L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of(eth, sol));
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(eth));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(sol));

        // ETH adapter succeeds; SOL adapter errors.
        WalletPort ethAdapter = mock(WalletPort.class);
        when(ethAdapter.chain()).thenReturn("ETHEREUM");
        when(ethAdapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        WalletPort solAdapter = mock(WalletPort.class);
        when(solAdapter.chain()).thenReturn("SOLANA");
        when(solAdapter.fetchBalances(any())).thenThrow(new WalletRpcException("Solana getBalance: RPC error"));

        // Happy-path wiring for the ETH sync.
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        WalletSyncService service = serviceWith(ethAdapter, solAdapter);

        WalletSyncService.ResyncSummary summary = service.resyncAll(MEMBER_ID);

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).containsExactly(Chain.SOLANA);
    }

    @Test
    void resyncAll_returnsEmptySummary_whenNoWallets() {
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of());

        WalletSyncService.ResyncSummary summary = serviceWith().resyncAll(MEMBER_ID);

        assertThat(summary.total()).isZero();
        assertThat(summary.succeeded()).isZero();
        assertThat(summary.failed()).isEmpty();
    }
}
