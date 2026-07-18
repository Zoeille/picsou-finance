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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("EVM");
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

        // Stale holdings pruned to exactly the tickers still held -- a token that
        // later disappears from the sync must not linger and inflate net worth.
        verify(accountService).pruneHoldings(eq(savedAccount), eq(Set.of("SOL", "USDC")));

        // Snapshot balance is the summed EUR value -- guards the conversion math.
        ArgumentCaptor<BigDecimal> balanceEur = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountService).upsertSnapshot(eq(savedAccount), balanceEur.capture(), any());
        assertThat(balanceEur.getValue()).isEqualByComparingTo("70.00");

        // lastSyncedAt was stamped and the wallet persisted.
        verify(walletRepository).save(wallet);
        assertThat(wallet.getLastSyncedAt()).isNotNull();
    }

    @Test
    void sync_keepsHeldAssets_whenPricesUnavailable() {
        // CoinGecko outage: refreshPrices returns an empty map. Held assets must
        // still be kept (pruneHoldings is keyed on held balances, not on which
        // prices resolved), so a transient price failure can't wipe holdings and
        // their cost basis.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("EVM");
        when(adapter.fetchBalances(any())).thenReturn(List.of(
            new WalletBalance("ETH", BigDecimal.ONE),
            new WalletBalance("USDC", new BigDecimal("50"))));
        when(priceService.refreshPrices(any())).thenReturn(Map.of()); // price outage

        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        WalletSyncService service = serviceWith(adapter);

        service.sync(1L, MEMBER_ID);

        // No prices -> nothing upserted, but the held tickers are kept: this must
        // NOT be an empty-set prune (which would delete every holding row).
        verify(accountService, never()).upsertHolding(any(), any(), any(), any(), any(), any());
        verify(accountService).pruneHoldings(eq(savedAccount), eq(Set.of("ETH", "USDC")));
    }

    @Test
    void sync_throwsSyncException_whenAdapterReturnsNoBalances() {
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("EVM");
        when(adapter.fetchBalances(any())).thenReturn(List.of());

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class);
    }

    @Test
    void resyncAll_reportsFailedChain_andKeepsSyncingOthers() {
        WalletAddress eth = wallet(1L, Chain.EVM, "0xabc");
        WalletAddress sol = wallet(2L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of(eth, sol));
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(eth));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(sol));

        // EVM adapter succeeds; SOL adapter errors.
        WalletPort ethAdapter = mock(WalletPort.class);
        when(ethAdapter.chain()).thenReturn("EVM");
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
    void addWallet_rejectsMalformedAddress_beforePersistingAnything() {
        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn("EVM");
        doThrow(new IllegalArgumentException("Invalid EVM address '0xnope'"))
            .when(adapter).validateAddress("0xnope");

        WalletSyncService service = serviceWith(adapter);

        // Surfaces as a 400 (IllegalArgumentException), not the 422 of a failed sync.
        assertThatThrownBy(() -> service.addWallet(Chain.EVM, "  0xnope  ", "Ledger", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);

        // The whole point: no row is written, so the unusable wallet can't linger and
        // fail every subsequent resync. Validation also runs on the TRIMMED value.
        verify(walletRepository, never()).save(any());
        verify(familyMemberRepository, never()).findById(any());
        verify(adapter, never()).fetchBalances(any());
    }

    @Test
    void addWallet_rejectsBlankAddress_evenOnChainsWithoutFormatValidation() {
        // BITCOIN has no validateAddress override (its encodings aren't cheaply checked
        // offline), so a blank address would otherwise sail through the gate and only
        // fail at sync -- as a retryable-looking 422, after calling the explorer with an
        // empty address in the URL path.
        // No chain() stub: the blank check fires before findAdapter is even consulted,
        // which is itself the point -- an empty address is rejected chain-agnostically.
        WalletPort adapter = mock(WalletPort.class);

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.addWallet(Chain.BITCOIN, "   ", "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addWallet(Chain.BITCOIN, null, "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);

        verify(walletRepository, never()).save(any());
        verify(adapter, never()).fetchBalances(any());
    }

    @Test
    void addWallet_rejectsOverlongAddress_beforeItReachesTheColumn() {
        // wallet_address.address is VARCHAR(200) and BITCOIN has no validateAddress
        // override, so a pasted seed phrase used to reach the insert and come back as a
        // 500 from the constraint violation.
        WalletPort adapter = mock(WalletPort.class);

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.addWallet(
            Chain.BITCOIN, "x".repeat(201), "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too long");

        verify(walletRepository, never()).save(any());
        verify(adapter, never()).fetchBalances(any());
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
