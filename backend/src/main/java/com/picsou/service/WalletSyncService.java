package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.WalletPort;
import com.picsou.port.WalletPort.WalletBalance;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.WalletAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class WalletSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalletSyncService.class);

    private final List<WalletPort> walletAdapters;
    private final WalletAddressRepository walletRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final PriceService priceService;

    public WalletSyncService(
        List<WalletPort> walletAdapters,
        WalletAddressRepository walletRepository,
        AccountRepository accountRepository,
        AccountService accountService,
        PriceService priceService
    ) {
        this.walletAdapters = walletAdapters;
        this.walletRepository = walletRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.priceService = priceService;
    }

    public AccountResponse addWallet(Chain chain, String address, String label) {
        WalletAddress wallet = WalletAddress.builder()
            .chain(chain)
            .address(address.trim())
            .label(label != null && !label.isBlank() ? label.trim() : null)
            .build();
        walletRepository.save(wallet);

        return sync(wallet.getId());
    }

    public AccountResponse sync(Long walletId) {
        WalletAddress wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        WalletPort adapter = findAdapter(wallet.getChain());

        try {
            WalletBalance balance = adapter.fetchBalance(wallet.getAddress());

            BigDecimal priceEur = priceService.getPriceEur(balance.nativeSymbol());
            BigDecimal balanceEur = BigDecimal.ZERO;
            if (priceEur != null) {
                balanceEur = balance.nativeAmount().multiply(priceEur).setScale(2, RoundingMode.HALF_UP);
            } else {
                log.warn("No EUR price for {} — wallet balance will be 0", balance.nativeSymbol());
            }

            wallet.setLastSyncedAt(Instant.now());
            walletRepository.save(wallet);

            String externalId = "wallet_" + wallet.getChain().name().toLowerCase() + "_" + wallet.getId();
            String name = wallet.getLabel() != null
                ? wallet.getLabel()
                : wallet.getChain().name() + " Wallet";

            return upsertAccount(externalId, name, balanceEur, balance.nativeSymbol());

        } catch (Exception ex) {
            throw new SyncException("Sync wallet " + wallet.getChain() + " échoué : " + ex.getMessage());
        }
    }

    public void removeWallet(Long walletId) {
        WalletAddress wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        String externalId = "wallet_" + wallet.getChain().name().toLowerCase() + "_" + wallet.getId();
        accountRepository.findByExternalAccountId(externalId).ifPresent(accountRepository::delete);
        walletRepository.delete(wallet);
        log.info("Removed wallet {} and associated account", walletId);
    }

    public void resyncAll() {
        List<WalletAddress> wallets = walletRepository.findAllByOrderByCreatedAtAsc();
        for (WalletAddress wallet : wallets) {
            try {
                sync(wallet.getId());
            } catch (Exception ex) {
                log.warn("Wallet resync failed for {} {}: {}", wallet.getChain(), wallet.getAddress(), ex.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<WalletStatusResponse> listWallets() {
        return walletRepository.findAllByOrderByCreatedAtAsc().stream()
            .map(w -> new WalletStatusResponse(
                w.getId(), w.getChain(), w.getAddress(), w.getLabel(), w.getLastSyncedAt()))
            .toList();
    }

    private WalletPort findAdapter(Chain chain) {
        return walletAdapters.stream()
            .filter(a -> a.chain().equalsIgnoreCase(chain.name()))
            .findFirst()
            .orElseThrow(() -> new SyncException("Aucun adapteur trouvé pour la chain : " + chain));
    }

    private AccountResponse upsertAccount(String externalId, String name, BigDecimal balanceEur, String ticker) {
        Optional<Account> existing = accountRepository.findByExternalAccountId(externalId);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(balanceEur);
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
                .name(name)
                .type(AccountType.CRYPTO)
                .provider(ticker)
                .currency("EUR")
                .currentBalance(balanceEur)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#f59e0b")
                .ticker(ticker)
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, balanceEur, LocalDate.now());
        return accountService.toResponse(account);
    }

    public record WalletStatusResponse(
        Long id, Chain chain, String address, String label, java.time.Instant lastSyncedAt) {}
}
