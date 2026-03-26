package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.DashboardResponse.DistributionItem;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Account;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final PriceService priceService;

    public DashboardService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        GoalService goalService,
        GoalRepository goalRepository,
        PriceService priceService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.priceService = priceService;
    }

    public DashboardResponse getDashboard() {
        List<Account> accounts = accountRepository.findAllByOrderByCreatedAtAsc();

        // Total net worth in EUR
        BigDecimal totalNetWorth = accounts.stream()
            .map(a -> priceService.toEur(a.getCurrentBalance(), a.getCurrency(), a.getTicker()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Net worth history (last 12 months)
        List<NetWorthPoint> history = buildNetWorthHistory();

        // Distribution (per account)
        List<DistributionItem> distribution = buildDistribution(accounts, totalNetWorth);

        // Goal summaries
        List<GoalProgressResponse> goals = goalRepository.findAllWithAccounts().stream()
            .map(goalService::toProgressResponse)
            .toList();

        return new DashboardResponse(totalNetWorth, history, distribution, goals);
    }

    private List<NetWorthPoint> buildNetWorthHistory() {
        LocalDate from = LocalDate.now().minusMonths(12);
        List<Object[]> rows = snapshotRepository.findDailyNetWorth(from);

        return rows.stream()
            .map(row -> new NetWorthPoint(
                ((java.sql.Date) row[0]).toLocalDate(),
                (BigDecimal) row[1]
            ))
            .toList();
    }

    private List<DistributionItem> buildDistribution(List<Account> accounts, BigDecimal totalNetWorth) {
        List<DistributionItem> items = new ArrayList<>();

        for (Account account : accounts) {
            BigDecimal balanceEur = priceService.toEur(
                account.getCurrentBalance(), account.getCurrency(), account.getTicker()
            );

            double percentage = totalNetWorth.compareTo(BigDecimal.ZERO) > 0
                ? balanceEur.divide(totalNetWorth, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue()
                : 0.0;

            items.add(new DistributionItem(
                account.getId(),
                account.getName(),
                account.getColor(),
                balanceEur,
                Math.round(percentage * 100.0) / 100.0
            ));
        }

        return items;
    }
}
