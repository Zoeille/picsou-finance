package com.picsou.service;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtRequest;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataRequest;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.RealEstateMetadata;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * Providers that report an authoritative EUR valuation for every holding.
     * Yahoo cannot quote some of their instruments -- and never quotes Amundi's
     * FCPE units -- so a partial live total would understate these accounts,
     * for épargne salariale all the way down to zero. See {@link #liveBalanceEur}.
     */
    private static final Set<String> PROVIDER_VALUED = Set.of(
        BourseDirectSyncService.PROVIDER,
        AmundiSyncService.PROVIDER
    );

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final RealEstateMetadataRepository realEstateMetadataRepository;
    private final DebtRepository debtRepository;
    private final PriceService priceService;
    private final LoanAmortizationService loanAmortizationService;

    public AccountService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        RealEstateMetadataRepository realEstateMetadataRepository,
        DebtRepository debtRepository,
        PriceService priceService,
        LoanAmortizationService loanAmortizationService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.realEstateMetadataRepository = realEstateMetadataRepository;
        this.debtRepository = debtRepository;
        this.priceService = priceService;
        this.loanAmortizationService = loanAmortizationService;
    }

    public List<AccountResponse> findAll(Long memberId) {
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(this::toResponse)
            .toList();
    }

    public AccountResponse findById(Long id, Long memberId) {
        return toResponse(getOrThrow(id, memberId));
    }

    @Transactional
    public AccountResponse create(AccountRequest req, FamilyMember member) {
        Account account = Account.builder()
            .member(member)
            .name(req.name())
            .type(req.type())
            .provider(req.provider())
            .currency(req.currency())
            .currentBalance(req.currentBalance() != null ? req.currentBalance() : BigDecimal.ZERO)
            .isManual(req.isManual())
            .color(req.color() != null ? req.color() : "#6366f1")
            .ticker(req.ticker())
            .build();

        account = accountRepository.save(account);

        // Create initial snapshot if balance is provided
        if (account.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal invested = calculateInvestedAmount(account);
            createSnapshot(account, account.getCurrentBalance(), invested, LocalDate.now());
        }

        return toResponse(account);
    }

    @Transactional
    public AccountResponse update(Long id, AccountRequest req, Long memberId) {
        Account account = getOrThrow(id, memberId);

        account.setName(req.name());
        account.setType(req.type());
        account.setProvider(req.provider());
        account.setCurrency(req.currency());
        account.setColor(req.color() != null ? req.color() : account.getColor());
        account.setTicker(req.ticker());

        // For manual accounts, allow balance update
        if (account.isManual() && req.currentBalance() != null) {
            BigDecimal oldBalance = account.getCurrentBalance();
            account.setCurrentBalance(req.currentBalance());
            if (req.currentBalance().compareTo(oldBalance) != 0) {
                upsertSnapshot(account, req.currentBalance(), LocalDate.now());
            }
        }

        return toResponse(accountRepository.save(account));
    }

    @Transactional
    public void delete(Long id, Long memberId) {
        Account account = getOrThrow(id, memberId);
        account.setDeletedAt(Instant.now());
        accountRepository.save(account);
    }

    @Transactional
    public BalanceSnapshot addManualSnapshot(Long accountId, Long memberId, SnapshotRequest req) {
        Account account = getOrThrow(accountId, memberId);

        // Update current balance if this is the most recent snapshot
        Optional<BalanceSnapshot> latest = snapshotRepository.findLatestByAccountId(accountId);
        if (latest.isEmpty() || !req.date().isBefore(latest.get().getDate())) {
            account.setCurrentBalance(req.balance());
            account.setLastSyncedAt(Instant.now());
            accountRepository.save(account);
        }

        return upsertSnapshot(account, req.balance(), req.date());
    }

    public List<BalanceSnapshot> getHistory(Long accountId, Long memberId, LocalDate from, LocalDate to) {
        getOrThrow(accountId, memberId); // validate account exists
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(12);
        return snapshotRepository.findByAccountIdAndDateBetweenOrderByDateAsc(accountId, effectiveFrom, effectiveTo);
    }

    public List<HoldingResponse> getHoldings(Long accountId, Long memberId) {
        getOrThrow(accountId, memberId); // validate account exists
        return holdingRepository.findByAccountIdOrderByCurrentPriceDesc(accountId).stream()
            .map(this::toHoldingResponse)
            .toList();
    }

    public List<TransactionResponse> getTransactions(Long accountId, Long memberId) {
        getOrThrow(accountId, memberId); // validate account exists
        return transactionRepository.findByAccountIdOrderByDateDesc(accountId).stream()
            .map(TransactionResponse::from)
            .toList();
    }

    @Transactional
    public AccountHolding upsertHolding(Long accountId, Long memberId, String ticker, String name,
                                         BigDecimal quantity, BigDecimal currentPriceEur) {
        Account account = getOrThrow(accountId, memberId);
        Optional<AccountHolding> existing = holdingRepository.findByAccountIdAndTicker(accountId, ticker);
        AccountHolding holding;
        if (existing.isPresent()) {
            holding = existing.get();
            holding.setQuantity(quantity);
            holding.setCurrentPrice(currentPriceEur);
            holding.setLastSyncedAt(Instant.now());
            // Keep averageBuyIn unchanged — it's the cost basis from first sync
        } else {
            holding = AccountHolding.builder()
                .account(account)
                .ticker(ticker)
                .name(name)
                .quantity(quantity)
                .averageBuyIn(currentPriceEur) // baseline: no PnL at first sync
                .currentPrice(currentPriceEur)
                .lastSyncedAt(Instant.now())
                .build();
        }
        return holdingRepository.save(holding);
    }

    /**
     * Removes holdings of {@code account} whose ticker is not in {@code keepTickers}
     * — i.e. assets the latest sync no longer reports as <em>held</em> (keyed on the
     * balances the adapter returned, never on which prices happened to resolve, so a
     * transient price outage cannot delete a still-held asset). Without this, a sold
     * or moved-out holding lingers at its last quantity and inflates the account's
     * live balance ({@link #liveBalanceEur}) and invested basis forever. An empty
     * {@code keepTickers} clears all holdings (the wallet holds nothing priced/known).
     *
     * <p>Takes the already-resolved {@link Account} (the caller has just loaded and
     * member-scoped it), so no extra ownership lookup is issued on the sync path.
     */
    @Transactional
    public void pruneHoldings(Account account, Set<String> keepTickers) {
        if (keepTickers.isEmpty()) {
            holdingRepository.deleteByAccountId(account.getId());
        } else {
            holdingRepository.deleteByAccountIdAndTickerNotIn(account.getId(), keepTickers);
        }
    }

    // ─── Package-private helpers used by other services ──────────────────────

    /**
     * Calculate the invested amount (cost basis) for an account.
     * For accounts with holdings: SUM(quantity × averageBuyIn).
     * For cash accounts: same as the current balance.
     */
    public BigDecimal calculateInvestedAmount(Account account) {
        List<AccountHolding> holdings = holdingRepository.findByAccount_Id(account.getId());
        if (holdings.isEmpty()) {
            return account.getCurrentBalance();
        }
        BigDecimal invested = account.getCashBalance() != null
            ? account.getCashBalance()
            : BigDecimal.ZERO;
        for (AccountHolding holding : holdings) {
            BigDecimal costBasis = providerCostBasisEur(holding);
            if (costBasis == null && holding.getAverageBuyIn() != null) {
                costBasis = holding.getAverageBuyIn().multiply(holding.getQuantity());
            }
            if (costBasis == null) {
                // A partial cost basis creates a fictitious gain. Until every
                // position is known, use the account value as a neutral baseline.
                return account.getCurrentBalance();
            }
            invested = invested.add(costBasis);
        }
        return invested;
    }

    BalanceSnapshot upsertSnapshot(Account account, BigDecimal balance, LocalDate date) {
        BigDecimal invested = calculateInvestedAmount(account);
        return upsertSnapshot(account, balance, invested, date);
    }

    BalanceSnapshot upsertSnapshot(Account account, BigDecimal balance, BigDecimal investedAmount, LocalDate date) {
        Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), date);
        if (existing.isPresent()) {
            BalanceSnapshot snap = existing.get();
            snap.setBalance(balance);
            snap.setInvestedAmount(investedAmount);
            return snapshotRepository.save(snap);
        }
        return createSnapshot(account, balance, investedAmount, date);
    }

    private BalanceSnapshot createSnapshot(Account account, BigDecimal balance, BigDecimal investedAmount, LocalDate date) {
        return snapshotRepository.save(BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .investedAmount(investedAmount)
            .build());
    }

    Account getOrThrow(Long id, Long memberId) {
        return accountRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(id));
    }

    /**
     * Returns the live balance in EUR for an account.
     * For accounts with holdings, computes live total from current prices.
     * For cash accounts, returns the stored balance converted to EUR.
     */
    public BigDecimal liveBalanceEur(Account account) {
        if (account.getType() == AccountType.LOAN) {
            return debtRepository.findByAccountId(account.getId())
                .map(debt -> loanAmortizationService.computeRemainingBalance(debt, LocalDate.now()))
                .orElseGet(() -> priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker()));
        }
        List<AccountHolding> holdings = holdingRepository.findByAccount_Id(account.getId());
        if (holdings.isEmpty()) {
            return priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
        }
        BigDecimal liveValue = account.getCashBalance() != null ? account.getCashBalance() : BigDecimal.ZERO;
        boolean allHoldingsPriced = true;
        for (AccountHolding h : holdings) {
            BigDecimal qty = h.getQuantity();
            BigDecimal livePrice = h.getTicker() != null ? priceService.getPriceEur(h.getTicker()) : null;
            if (livePrice == null) {
                allHoldingsPriced = false;
                // Skipping is deliberate -- a held-but-unpriced asset must not be valued at a
                // guess -- but it is not free: during a price-provider outage the balance (and
                // any snapshot taken from it) silently shrinks by whatever those holdings were
                // worth. Log it so the dip is explicable rather than mysterious.
                // signum() != 0 (not > 0): omitting an unpriced SHORT overstates the
                // balance — a liability valued at 0 — which deserves the trace at least
                // as much as the understated long.
                if (qty != null && qty.signum() != 0) {
                    log.warn("No EUR price for holding {} (account {}) -- excluding it from the live balance",
                        h.getTicker(), account.getId());
                }
                continue;
            }
            liveValue = liveValue.add(qty.multiply(livePrice));
        }
        // Some providers report an authoritative total in EUR. If Yahoo/OpenFIGI cannot
        // price even one instrument, prefer that last successful provider valuation over a
        // misleading partial total (cash + only the symbols Yahoo happened to resolve).
        if (!allHoldingsPriced && isProviderValued(account)) {
            return account.getCurrentBalance();
        }
        return liveValue;
    }

    /** Null-safe: {@code Set.of(...)} throws on a null lookup, and most accounts have no provider. */
    private boolean isProviderValued(Account account) {
        return account.getProvider() != null && PROVIDER_VALUED.contains(account.getProvider());
    }

    /**
     * Live balance in EUR with liability sign applied: LOAN accounts return a
     * NEGATIVE value (outstanding debt), all other types return liveBalanceEur as-is.
     * Use this for any net-worth-style summation.
     */
    public BigDecimal signedLiveBalanceEur(Account account) {
        BigDecimal value = liveBalanceEur(account);
        return account.getType() == AccountType.LOAN ? value.negate() : value;
    }

    AccountResponse toResponse(Account account) {
        BigDecimal balanceEur = liveBalanceEur(account);
        AccountResponse response = AccountResponse.from(account, balanceEur);

        if (account.getType() == AccountType.REAL_ESTATE) {
            Optional<RealEstateMetadataResponse> meta = realEstateMetadataRepository.findByAccountId(account.getId())
                .map(RealEstateMetadataResponse::from);
            if (meta.isPresent()) {
                response = response.withRealEstate(meta.get());
            }
        }

        if (account.getType() == AccountType.LOAN) {
            Optional<DebtResponse> debt = debtRepository.findByAccountId(account.getId())
                .map(DebtResponse::from);
            if (debt.isPresent()) {
                response = response.withDebt(debt.get());
            }
        }

        return response;
    }

    @Transactional
    public HoldingResponse updateHolding(Long accountId, Long memberId, String ticker,
            BigDecimal quantity, BigDecimal averageBuyIn) {
        getOrThrow(accountId, memberId);
        AccountHolding h = holdingRepository.findByAccountIdAndTicker(accountId, ticker)
            .orElseThrow(() -> new ResourceNotFoundException("Holding not found"));
        h.setQuantity(quantity);
        if (averageBuyIn != null) h.setAverageBuyIn(averageBuyIn);
        // A user edit invalidates broker-derived valuation/P&L as a coherent
        // pair. A subsequent provider sync will repopulate both fields.
        h.setProviderValueEur(null);
        h.setProviderPnlEur(null);
        holdingRepository.save(h);
        return toHoldingResponse(h);
    }

    @Transactional
    public void deleteHolding(Long accountId, Long memberId, String ticker) {
        getOrThrow(accountId, memberId);
        AccountHolding h = holdingRepository.findByAccountIdAndTicker(accountId, ticker)
            .orElseThrow(() -> new ResourceNotFoundException("Holding not found"));
        holdingRepository.delete(h);
    }

    @Transactional
    public RealEstateMetadataResponse updateRealEstateMetadata(Long accountId, Long memberId, RealEstateMetadataRequest req) {
        Account account = getOrThrow(accountId, memberId);

        RealEstateMetadata metadata = realEstateMetadataRepository.findByAccountId(accountId)
            .orElseGet(() -> RealEstateMetadata.builder().account(account).build());

        metadata.setPurchasePrice(req.purchasePrice());
        metadata.setPurchaseDate(req.purchaseDate());
        metadata.setSurfaceArea(req.surfaceArea());
        metadata.setAddress(req.address());
        metadata.setPropertyType(req.propertyType());
        metadata.setRentalIncome(req.rentalIncome() != null ? req.rentalIncome() : BigDecimal.ZERO);

        return RealEstateMetadataResponse.from(realEstateMetadataRepository.save(metadata));
    }

    @Transactional
    public DebtResponse updateDebtMetadata(Long accountId, Long memberId, DebtRequest req) {
        Account account = getOrThrow(accountId, memberId);

        Debt debt = debtRepository.findByAccountId(accountId)
            .orElseGet(() -> Debt.builder()
                .account(account)
                .member(account.getMember())
                .build());

        if (req.linkedAccountId() != null) {
            // Member-scope the linked account like every other lookup in this service —
            // never resolve a request-supplied account id without the member filter.
            Account linked = getOrThrow(req.linkedAccountId(), memberId);
            debt.setLinkedAccount(linked);
        } else {
            debt.setLinkedAccount(null);
        }

        debt.setBorrowedAmount(req.borrowedAmount());
        debt.setInterestRate(req.interestRate());
        debt.setMonthlyPayment(req.monthlyPayment());
        debt.setLenderName(req.lenderName());
        debt.setStartDate(req.startDate());
        debt.setEndDate(req.endDate());
        debt.setInsuranceMonthly(req.insuranceMonthly());
        debt.setFileFees(req.fileFees());

        return DebtResponse.from(debtRepository.save(debt));
    }

    public LoanAmortizationService.LoanScheduleResponse getLoanSummary(Long accountId, Long memberId) {
        Account account = getOrThrow(accountId, memberId);
        if (account.getType() != AccountType.LOAN) {
            throw new IllegalArgumentException("Account is not a loan");
        }
        Debt debt = debtRepository.findByAccountId(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Debt details not set for account"));
        return loanAmortizationService.compute(debt, LocalDate.now());
    }

    private HoldingResponse toHoldingResponse(AccountHolding holding) {
        BigDecimal currentPrice = holding.getCurrentPrice();
        BigDecimal currentPriceEur = null;
        Instant priceUpdatedAt = null;

        // Only PriceService (Yahoo/CoinGecko, FX-converted) is trusted as a
        // source of EUR-denominated prices. holding.currentPrice may have been
        // stored by a broker adapter (TR/Bourso) in the security's native
        // currency without conversion — using it as a fallback would silently
        // produce native-as-EUR values. Better to return null and surface
        // "price unknown" than to invent a wrong number.
        if (holding.getTicker() != null && !holding.getTicker().isBlank()) {
            currentPriceEur = priceService.getPriceEur(holding.getTicker());
            priceUpdatedAt = holding.getLastSyncedAt();
        }

        BigDecimal quantity = holding.getQuantity();
        BigDecimal averageBuyIn = holding.getAverageBuyIn();
        BigDecimal costBasis = providerCostBasisEur(holding);
        if (costBasis == null && averageBuyIn != null) {
            costBasis = averageBuyIn.multiply(quantity);
        }
        BigDecimal currentValueEur = currentPriceEur != null
            ? currentPriceEur.multiply(quantity)
            : holding.getProviderValueEur();
        BigDecimal pnlEur = currentValueEur != null && costBasis != null
            ? currentValueEur.subtract(costBasis)
            : holding.getProviderPnlEur();
        // abs(): a short position has a negative cost basis, and dividing by it would
        // flip the sign — a winning short would display as a loss. The percentage must
        // carry the sign of the P&L itself, the denominator is only a magnitude.
        // The null check on costBasis is required since pnlEur can now fall back to the
        // provider-reported P&L, which is available even when no cost basis is known.
        BigDecimal pnlPercent = (pnlEur != null && costBasis != null && costBasis.signum() != 0)
            ? pnlEur.divide(costBasis.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : null;

        return new HoldingResponse(
            holding.getTicker(),
            holding.getName(),
            quantity,
            averageBuyIn,
            currentPrice,
            holding.getQuoteCurrency(),
            currentValueEur,
            costBasis,
            pnlEur,
            pnlPercent,
            priceUpdatedAt
        );
    }

    private BigDecimal providerCostBasisEur(AccountHolding holding) {
        if (holding.getProviderValueEur() == null || holding.getProviderPnlEur() == null) {
            return null;
        }
        return holding.getProviderValueEur().subtract(holding.getProviderPnlEur());
    }
}
