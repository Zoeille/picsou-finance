package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.exception.WalletRpcException;
import com.picsou.model.*;
import com.picsou.port.WalletPort;
import com.picsou.port.WalletPort.WalletBalance;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WalletAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class WalletSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalletSyncService.class);

    private final List<WalletPort> walletAdapters;
    private final WalletAddressRepository walletRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final PriceService priceService;

    public WalletSyncService(
        List<WalletPort> walletAdapters,
        WalletAddressRepository walletRepository,
        AccountRepository accountRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        PriceService priceService
    ) {
        this.walletAdapters = walletAdapters;
        this.walletRepository = walletRepository;
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.priceService = priceService;
    }

    public AccountResponse addWallet(Chain chain, String address, String label, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        WalletAddress wallet = WalletAddress.builder()
            .member(member)
            .chain(chain)
            .address(address.trim())
            .label(label != null && !label.isBlank() ? label.trim() : null)
            .build();
        walletRepository.save(wallet);

        return sync(wallet.getId(), memberId);
    }

    public AccountResponse sync(Long walletId, Long memberId) {
        WalletAddress wallet = walletRepository.findByIdAndMemberId(walletId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        WalletPort adapter = findAdapter(wallet.getChain());

        try {
            List<WalletBalance> balances = adapter.fetchBalances(wallet.getAddress());
            // WalletPort.fetchBalances contracts a non-null list with at least the native
            // asset; isEmpty() stays only as a cheap guard against a misbehaving adapter.
            if (balances.isEmpty()) {
                throw new SyncException("Adapter returned no balances for " + wallet.getChain());
            }

            // The chain's native asset (SOL, ETH, BTC) is the first entry by
            // contract — keep it as the account's display ticker.
            String nativeSymbol = balances.get(0).symbol();

            Set<String> tickers = balances.stream()
                .map(b -> b.symbol().toUpperCase())
                .collect(Collectors.toSet());
            Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

            BigDecimal balanceEur = BigDecimal.ZERO;
            for (WalletBalance b : balances) {
                BigDecimal priceEur = prices.get(b.symbol().toUpperCase());
                if (priceEur != null) {
                    balanceEur = balanceEur.add(
                        b.amount().multiply(priceEur).setScale(2, RoundingMode.HALF_UP));
                } else {
                    log.warn("No EUR price for {} -- skipping in wallet total", b.symbol());
                }
            }

            wallet.setLastSyncedAt(Instant.now());
            walletRepository.save(wallet);

            String externalId = "wallet_" + wallet.getChain().name().toLowerCase() + "_" + wallet.getId();
            String name = wallet.getLabel() != null
                ? wallet.getLabel()
                : wallet.getChain().name() + " Wallet";

            Account account = resolveAccount(externalId, name, balanceEur, nativeSymbol, memberId);

            for (WalletBalance b : balances) {
                BigDecimal priceEur = prices.get(b.symbol().toUpperCase());
                if (priceEur != null && b.amount().signum() > 0) {
                    accountService.upsertHolding(account.getId(), memberId,
                        b.symbol().toUpperCase(), b.symbol().toUpperCase(),
                        b.amount(), priceEur);
                }
            }

            accountService.upsertSnapshot(account, balanceEur, LocalDate.now());

            return accountService.toResponse(account);

        } catch (WalletRpcException | SyncException ex) {
            // Expected external failure (bad RPC response, no balances): a routine
            // sync problem, not a bug. Keep the friendly 422 the user sees.
            log.warn("Wallet sync failed for {} {}: {}", wallet.getChain(), wallet.getAddress(), ex.getMessage());
            throw new SyncException("Could not sync your " + wallet.getChain() + " wallet. Please try again later.", ex);
        } catch (Exception ex) {
            // Anything else (NPE, ClassCastException...) is a genuine bug -- log it
            // at ERROR with the full stacktrace so it doesn't hide as a transient sync.
            log.error("Unexpected error during wallet sync for {} {}", wallet.getChain(), wallet.getAddress(), ex);
            throw new SyncException("Could not sync your " + wallet.getChain() + " wallet. Please try again later.", ex);
        }
    }

    public void removeWallet(Long walletId, Long memberId) {
        WalletAddress wallet = walletRepository.findByIdAndMemberId(walletId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        String externalId = "wallet_" + wallet.getChain().name().toLowerCase() + "_" + wallet.getId();
        accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId)
            .ifPresent(accountRepository::delete);
        walletRepository.delete(wallet);
        log.info("Removed wallet {} and associated account", walletId);
    }

    public ResyncSummary resyncAll(Long memberId) {
        List<WalletAddress> wallets = walletRepository.findAllByMemberId(memberId);
        int succeeded = 0;
        List<Chain> failed = new ArrayList<>();
        for (WalletAddress wallet : wallets) {
            try {
                sync(wallet.getId(), memberId);
                succeeded++;
            } catch (Exception ex) {
                log.error("Wallet resync failed for {} {}", wallet.getChain(), wallet.getAddress(), ex);
                failed.add(wallet.getChain());
            }
        }
        return new ResyncSummary(wallets.size(), succeeded, failed);
    }

    @Transactional(readOnly = true)
    public List<WalletStatusResponse> listWallets(Long memberId) {
        return walletRepository.findAllByMemberId(memberId).stream()
            .map(w -> new WalletStatusResponse(
                w.getId(), w.getChain(), w.getAddress(), w.getLabel(), w.getLastSyncedAt()))
            .toList();
    }

    private WalletPort findAdapter(Chain chain) {
        return walletAdapters.stream()
            .filter(a -> a.chain().equalsIgnoreCase(chain.name()))
            .findFirst()
            .orElseThrow(() -> new SyncException("This wallet type isn't supported yet."));
    }

    private Account resolveAccount(String externalId, String name, BigDecimal balanceEur, String ticker, Long memberId) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(balanceEur);
            account.setLastSyncedAt(Instant.now());
            account.setTicker(null);
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(name)
                .type(AccountType.CRYPTO)
                .provider(ticker)  // provider keeps the symbol for display (BTC, SOL...)
                .currency("EUR")
                .currentBalance(balanceEur)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#f59e0b")
                .build();
        }

        return accountRepository.save(account);
    }

    public record WalletStatusResponse(
        Long id, Chain chain, String address, String label, java.time.Instant lastSyncedAt) {}

    /** Outcome of a batch resync: how many wallets were tried, how many synced, and which chains failed. */
    public record ResyncSummary(int total, int succeeded, List<Chain> failed) {}
}
