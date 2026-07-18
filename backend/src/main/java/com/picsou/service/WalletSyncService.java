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
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class WalletSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalletSyncService.class);

    /** Matches the {@code wallet_address.address} column width (see V10). */
    private static final int MAX_ADDRESS_LENGTH = 200;

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
        // AddWalletRequest carries no @NotNull and the controller no @Valid, so both
        // fields arrive null for a malformed body. findAdapter would then match no adapter
        // and throw SyncException (422) -- misleading for what is a malformed request, and
        // it says "isn't supported yet" about a chain the caller never named. Reject it
        // here as the 400 it is.
        if (chain == null) {
            throw new IllegalArgumentException("A wallet chain is required");
        }
        String trimmed = address == null ? "" : address.trim();

        // Reject a missing address here rather than in validateAddress: that hook is a
        // no-op by default, so an empty string would otherwise sail past it on BITCOIN
        // and SOLANA and reach the explorer as an empty path segment.
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A wallet address is required");
        }
        // wallet_address.address is VARCHAR(200) and validateAddress is a no-op for
        // BITCOIN/SOLANA, so without this a pasted seed phrase or wall of text reaches the
        // insert and comes back as a 500 from the constraint violation -- the same
        // bad-input-becomes-500 this method exists to prevent for EVM. Bitcoin output
        // descriptors are the longest legitimate value and sit well under 200.
        if (trimmed.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                "Wallet address is too long (" + trimmed.length() + " characters, maximum "
                    + MAX_ADDRESS_LENGTH + ")");
        }

        // Check the address format before the sync attempt, so a typo is a 400 naming the
        // expected format instead of a 422 "try again later" for input that can never
        // succeed -- and costs no call to the RPC/explorer. This is NOT what stops a bad
        // row from being persisted: addWallet is @Transactional, so a throwing sync()
        // already rolls the insert back. Keep both; the transaction is the durability
        // guarantee, this is the error quality one.
        findAdapter(chain).validateAddress(trimmed);

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        WalletAddress wallet = WalletAddress.builder()
            .member(member)
            .chain(chain)
            .address(trimmed)
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
                .map(b -> b.symbol().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
            Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

            // Resolve each balance's ticker + price once, then reuse it for both the
            // EUR total and the holding upserts (one pass, one price lookup).
            record Priced(String ticker, BigDecimal amount, BigDecimal priceEur) {}
            List<Priced> priced = balances.stream()
                .map(b -> {
                    String ticker = b.symbol().toUpperCase(Locale.ROOT);
                    return new Priced(ticker, b.amount(), prices.get(ticker));
                })
                .toList();

            BigDecimal balanceEur = BigDecimal.ZERO;
            boolean anyHeld = false;
            boolean anyPriced = false;
            for (Priced p : priced) {
                if (p.amount().signum() > 0) anyHeld = true;
                if (p.priceEur() != null) {
                    anyPriced = true;
                    balanceEur = balanceEur.add(
                        p.amount().multiply(p.priceEur()).setScale(2, RoundingMode.HALF_UP));
                } else if (p.amount().signum() > 0) {
                    log.warn("No EUR price for {} -- skipping in wallet total", p.ticker());
                }
            }

            // A wallet that holds assets but priced none of them means the price provider is
            // down, NOT that the wallet is empty. Writing the resulting zero would set
            // account.currentBalance to 0 and stamp a 0 balance snapshot for today --
            // corrupting the net-worth history for a transient outage, and doing it silently
            // because the holdings themselves survive. Fail the sync instead: the wallet keeps
            // its last balance, nothing is written (this method is @Transactional), and the
            // user gets the same friendly 422 as any other transient sync failure. Same rule
            // as never reading a failed RPC as a 0 balance.
            //
            // A PARTIAL outage still writes a partial total, deliberately: refusing to snapshot
            // whenever any single obscure token is unpriced would block snapshots indefinitely.
            // That case is logged per ticker above.
            if (anyHeld && !anyPriced) {
                throw new SyncException(
                    "No EUR price available for any asset held in this wallet -- refusing to "
                        + "record a zero balance for " + wallet.getChain());
            }

            wallet.setLastSyncedAt(Instant.now());
            walletRepository.save(wallet);

            String externalId = "wallet_" + wallet.getChain().name().toLowerCase(Locale.ROOT) + "_" + wallet.getId();
            String name = wallet.getLabel() != null
                ? wallet.getLabel()
                : wallet.getChain().name() + " Wallet";

            Account account = resolveAccount(externalId, name, balanceEur, nativeSymbol, memberId);

            // Keep every asset actually held on-chain (positive balance), regardless
            // of whether its price resolved this cycle — a transient CoinGecko outage
            // must never delete a still-held holding (and its cost basis). Only priced
            // assets get their quantity/price refreshed; unpriced-but-held ones keep
            // their last row untouched.
            Set<String> keptTickers = new HashSet<>();
            for (Priced p : priced) {
                if (p.amount().signum() <= 0) continue;
                keptTickers.add(p.ticker());
                if (p.priceEur() != null) {
                    accountService.upsertHolding(account.getId(), memberId,
                        p.ticker(), p.ticker(), p.amount(), p.priceEur());
                }
            }
            // Drop holdings for assets no longer held (sold, moved to an exchange, or
            // a chain that dropped to zero) so stale rows don't keep inflating the
            // account's live balance. Keyed on held balances, never on prices.
            accountService.pruneHoldings(account, keptTickers);

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

        String externalId = "wallet_" + wallet.getChain().name().toLowerCase(Locale.ROOT) + "_" + wallet.getId();
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
            .filter(a -> a.chain() == chain)
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

        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race: another sync of this same wallet (a double-click, or a user sync
            // colliding with the scheduler) inserted the account between our lookup and this
            // insert. Re-resolve rather than fail -- two accounts for one wallet would split
            // its snapshot history in half. Note this narrows the window rather than closing
            // it: external_account_id carries only a plain index today, so the reconciliation
            // relies on whichever constraint the insert did violate.
            log.warn("Concurrent account creation for {} -- reusing the winning row", externalId);
            return accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId)
                .orElseThrow(() -> ex);
        }
    }

    /**
     * Fails fast at startup if any {@link Chain} has no adapter, or more than one. Dispatch in
     * {@link #findAdapter} is a {@code findFirst} over injected beans, so without this a missing
     * adapter surfaces only when a user syncs that chain (a 422 reading "isn't supported yet"),
     * and a duplicate silently lets whichever bean loaded first win.
     */
    @PostConstruct
    void verifyAdapterCoverage() {
        Map<Chain, Long> byChain = walletAdapters.stream()
            .collect(Collectors.groupingBy(WalletPort::chain, Collectors.counting()));

        Set<Chain> missing = EnumSet.allOf(Chain.class);
        missing.removeAll(byChain.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No WalletPort adapter for chain(s): " + missing);
        }

        List<Chain> duplicated = byChain.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("Multiple WalletPort adapters for chain(s): " + duplicated);
        }
    }

    public record WalletStatusResponse(
        Long id, Chain chain, String address, String label, java.time.Instant lastSyncedAt) {}

    /** Outcome of a batch resync: how many wallets were tried, how many synced, and which chains failed. */
    public record ResyncSummary(int total, int succeeded, List<Chain> failed) {}
}
