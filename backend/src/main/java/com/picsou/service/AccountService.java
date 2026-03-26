package com.picsou.service;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final PriceService priceService;

    public AccountService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        PriceService priceService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.priceService = priceService;
    }

    public List<AccountResponse> findAll() {
        return accountRepository.findAllByOrderByCreatedAtAsc().stream()
            .map(this::toResponse)
            .toList();
    }

    public AccountResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public AccountResponse create(AccountRequest req) {
        Account account = Account.builder()
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
            createSnapshot(account, account.getCurrentBalance(), LocalDate.now());
        }

        return toResponse(account);
    }

    @Transactional
    public AccountResponse update(Long id, AccountRequest req) {
        Account account = getOrThrow(id);

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
    public void delete(Long id) {
        if (!accountRepository.existsById(id)) {
            throw ResourceNotFoundException.account(id);
        }
        accountRepository.deleteById(id);
    }

    @Transactional
    public BalanceSnapshot addManualSnapshot(Long accountId, SnapshotRequest req) {
        Account account = getOrThrow(accountId);

        // Update current balance if this is the most recent snapshot
        Optional<BalanceSnapshot> latest = snapshotRepository.findLatestByAccountId(accountId);
        if (latest.isEmpty() || !req.date().isBefore(latest.get().getDate())) {
            account.setCurrentBalance(req.balance());
            account.setLastSyncedAt(Instant.now());
            accountRepository.save(account);
        }

        return upsertSnapshot(account, req.balance(), req.date());
    }

    public List<BalanceSnapshot> getHistory(Long accountId, LocalDate from, LocalDate to) {
        getOrThrow(accountId); // validate account exists
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(12);
        return snapshotRepository.findByAccountIdAndDateBetweenOrderByDateAsc(accountId, effectiveFrom, effectiveTo);
    }

    // ─── Package-private helpers used by other services ──────────────────────

    BalanceSnapshot upsertSnapshot(Account account, BigDecimal balance, LocalDate date) {
        Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), date);
        if (existing.isPresent()) {
            BalanceSnapshot snap = existing.get();
            snap.setBalance(balance);
            return snapshotRepository.save(snap);
        }
        return createSnapshot(account, balance, date);
    }

    private BalanceSnapshot createSnapshot(Account account, BigDecimal balance, LocalDate date) {
        return snapshotRepository.save(BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .build());
    }

    Account getOrThrow(Long id) {
        return accountRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.account(id));
    }

    AccountResponse toResponse(Account account) {
        BigDecimal balanceEur = priceService.toEur(
            account.getCurrentBalance(),
            account.getCurrency(),
            account.getTicker()
        );
        return AccountResponse.from(account, balanceEur);
    }
}
