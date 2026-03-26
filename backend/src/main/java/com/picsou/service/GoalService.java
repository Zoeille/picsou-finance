package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Goal;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class GoalService {

    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final AccountService accountService;

    public GoalService(
        GoalRepository goalRepository,
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        AccountService accountService
    ) {
        this.goalRepository = goalRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.accountService = accountService;
    }

    public List<GoalProgressResponse> findAll() {
        return goalRepository.findAllWithAccounts().stream()
            .map(this::toProgressResponse)
            .toList();
    }

    public GoalProgressResponse findById(Long id) {
        return toProgressResponse(getOrThrow(id));
    }

    @Transactional
    public GoalProgressResponse create(GoalRequest req) {
        List<Account> accounts = accountRepository.findAllById(req.accountIds());
        if (accounts.size() != req.accountIds().size()) {
            throw new IllegalArgumentException("One or more account IDs not found");
        }

        Goal goal = Goal.builder()
            .name(req.name())
            .targetAmount(req.targetAmount())
            .deadline(req.deadline())
            .accounts(new ArrayList<>(accounts))
            .build();

        return toProgressResponse(goalRepository.save(goal));
    }

    @Transactional
    public GoalProgressResponse update(Long id, GoalRequest req) {
        Goal goal = getOrThrow(id);

        List<Account> accounts = accountRepository.findAllById(req.accountIds());
        if (accounts.size() != req.accountIds().size()) {
            throw new IllegalArgumentException("One or more account IDs not found");
        }

        goal.setName(req.name());
        goal.setTargetAmount(req.targetAmount());
        goal.setDeadline(req.deadline());
        goal.setAccounts(new ArrayList<>(accounts));

        return toProgressResponse(goalRepository.save(goal));
    }

    @Transactional
    public void delete(Long id) {
        if (!goalRepository.existsById(id)) throw ResourceNotFoundException.goal(id);
        goalRepository.deleteById(id);
    }

    // ─── Progress calculation ─────────────────────────────────────────────────

    GoalProgressResponse toProgressResponse(Goal goal) {
        List<AccountResponse> accountResponses = goal.getAccounts().stream()
            .map(accountService::toResponse)
            .toList();

        BigDecimal currentTotal = accountResponses.stream()
            .map(AccountResponse::currentBalanceEur)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal target = goal.getTargetAmount();
        long monthsLeft = Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), goal.getDeadline()));

        BigDecimal needed = target.subtract(currentTotal);
        BigDecimal monthlyNeeded;

        if (monthsLeft > 0) {
            monthlyNeeded = needed.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP);
        } else {
            monthlyNeeded = needed; // deadline passed or this month
        }

        BigDecimal percentComplete = target.compareTo(BigDecimal.ZERO) > 0
            ? currentTotal.divide(target, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;

        BigDecimal avgMonthlyContribution = calculateAvgMonthlyContribution(goal.getAccounts());
        // null = no history yet → give benefit of the doubt (not "late")
        boolean isOnTrack = monthlyNeeded.compareTo(BigDecimal.ZERO) <= 0
            || avgMonthlyContribution == null
            || avgMonthlyContribution.compareTo(monthlyNeeded) >= 0;

        BigDecimal surplus = avgMonthlyContribution != null
            ? avgMonthlyContribution.subtract(monthlyNeeded)
            : BigDecimal.ZERO;

        return GoalProgressResponse.from(
            goal, accountResponses, currentTotal, percentComplete,
            monthsLeft, monthlyNeeded, avgMonthlyContribution, isOnTrack, surplus
        );
    }

    /**
     * Calculates average monthly contribution over the last 3 months
     * by comparing balance snapshots (first vs last, averaged over elapsed months).
     * Returns null when no snapshot history is available yet.
     */
    private BigDecimal calculateAvgMonthlyContribution(List<Account> accounts) {
        if (accounts.isEmpty()) return null;

        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        BigDecimal totalContribution = BigDecimal.ZERO;
        int accountsWithData = 0;

        for (Account account : accounts) {
            List<BalanceSnapshot> snapshots = snapshotRepository
                .findRecentByAccountId(account.getId(), threeMonthsAgo);

            if (snapshots.size() >= 2) {
                BigDecimal first = snapshots.get(0).getBalance();
                BigDecimal last = snapshots.get(snapshots.size() - 1).getBalance();
                long months = Math.max(1, ChronoUnit.MONTHS.between(
                    snapshots.get(0).getDate(),
                    snapshots.get(snapshots.size() - 1).getDate()
                ));
                totalContribution = totalContribution.add(
                    last.subtract(first).divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)
                );
                accountsWithData++;
            }
        }

        if (accountsWithData == 0) return null;
        return totalContribution.divide(BigDecimal.valueOf(accountsWithData), 2, RoundingMode.HALF_UP);
    }

    private Goal getOrThrow(Long id) {
        return goalRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.goal(id));
    }
}
