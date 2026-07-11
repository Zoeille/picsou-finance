package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ManualTransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingComputeService holdingComputeService;
    private final FinaryPersistenceHelper finaryPersistenceHelper;
    private final CategoryRepository categoryRepository;
    private final CategorizationService categorizationService;
    private final OpenFigiIsinConverter openFigiIsinConverter;

    private static final Set<AccountType> INVESTMENT_TYPES =
        Set.of(AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CRYPTO);

    @Transactional
    public TransactionResponse addTransaction(Long accountId, Long memberId, TransactionRequest req) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = Transaction.builder()
            .account(account)
            .date(req.date())
            .description(req.description())
            .amount(req.amount())
            .txType(req.txType())
            .quantity(req.quantity())
            .pricePerUnit(req.pricePerUnit())
            .isManual(true)
            .nativeCurrency(req.currency() != null ? req.currency() : "EUR")
            .build();
        applyInstrumentFields(tx, req);

        // Explicit category wins; otherwise run the zero-config pipeline (clean label + brand
        // link are stamped either way, and a rule or known brand may auto-assign the category).
        if (req.categoryId() != null) {
            tx.setCategoryRef(resolveCategory(req.categoryId(), memberId));
            categorizationService.enrich(tx);
        } else {
            categorizationService.autoCategorize(tx, memberId);
        }

        transactionRepository.save(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            refreshManualCashBalance(account);
        }

        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransactionResponse updateTransaction(Long accountId, Long txId, Long memberId, TransactionRequest req) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = transactionRepository.findByIdAndAccountId(txId, account.getId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(txId));

        if (!tx.isManual()) {
            throw new IllegalArgumentException("Cannot edit a synced transaction");
        }

        tx.setDate(req.date());
        tx.setDescription(req.description());
        tx.setAmount(req.amount());
        if (req.txType() != null) tx.setTxType(req.txType());
        if (req.quantity() != null) tx.setQuantity(req.quantity());
        if (req.pricePerUnit() != null) tx.setPricePerUnit(req.pricePerUnit());
        if (req.currency() != null) tx.setNativeCurrency(req.currency());
        applyInstrumentFields(tx, req);
        if (req.categoryId() != null) tx.setCategoryRef(resolveCategory(req.categoryId(), memberId));
        transactionRepository.save(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            refreshManualCashBalance(account);
        }

        return TransactionResponse.from(tx);
    }

    @Transactional
    public void deleteTransaction(Long accountId, Long txId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = transactionRepository.findByIdAndAccountId(txId, account.getId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(txId));

        if (!tx.isManual()) {
            throw new IllegalArgumentException("Cannot delete a synced transaction");
        }

        transactionRepository.delete(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            refreshManualCashBalance(account);
        }
    }

    private Category resolveCategory(Long categoryId, Long memberId) {
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    /**
     * Normalizes the instrument fields of a BUY/SELL transaction.
     *
     * <p>When the ticker field holds an ISIN, it is resolved (via OpenFIGI) to a Yahoo
     * ticker + display name, so an ISIN entry and the equivalent ticker entry merge into
     * one position and Yahoo pricing works. A user-supplied {@code name} always wins over
     * the resolved one. The description is owned here so a raw ISIN never surfaces in the row.
     *
     * <p>No-op for cash transactions (they carry no ticker), preserving the caller's description.
     */
    private void applyInstrumentFields(Transaction tx, TransactionRequest req) {
        String input = req.ticker();
        if (input == null || input.isBlank()) {
            return; // cash transaction — leave description/ticker/name as-is
        }

        String resolvedTicker;
        String resolvedName;
        if (OpenFigiIsinConverter.isIsin(input)) {
            OpenFigiIsinConverter.TickerResult r = openFigiIsinConverter.resolve(input);
            resolvedTicker = r.ticker();   // already falls back to the ISIN itself on failure
            resolvedName = r.name();
        } else {
            resolvedTicker = input.trim().toUpperCase();
            resolvedName = null;
        }

        String effectiveName = (req.name() != null && !req.name().isBlank())
            ? req.name().trim()
            : resolvedName;

        tx.setTicker(resolvedTicker);
        tx.setName(effectiveName);
        tx.setDescription(effectiveName != null
            ? effectiveName
            : (tx.getTxType() == TransactionType.SELL ? "Vente " : "Achat ") + resolvedTicker);
    }

    /**
     * Refresh a cash account's derived balance and snapshot history after a manual change — but
     * only for {@link Account#isManual() manual} accounts, whose balance IS the transaction ledger.
     * A synced account's balance and history are owned by its connector; recomputing them from a
     * partial ledger (e.g. a TR Cash account that imported CSV history without the internal
     * transfers) would corrupt the balance, so they are left untouched until the next sync.
     */
    private void refreshManualCashBalance(Account account) {
        if (!account.isManual()) {
            return;
        }
        recomputeCashBalance(account);
        finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
    }

    private void recomputeCashBalance(Account account) {
        BigDecimal sum = transactionRepository.sumAmountByAccountId(account.getId());
        account.setCurrentBalance(sum);
        accountRepository.save(account);
    }
}
