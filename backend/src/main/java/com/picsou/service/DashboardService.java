package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.DashboardResponse.DistributionItem;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final AccountRepository accountRepository;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final PriceService priceService;
    private final AccountHoldingRepository holdingRepository;
    private final HistoryService historyService;

    public DashboardService(
        AccountRepository accountRepository,
        GoalService goalService,
        GoalRepository goalRepository,
        PriceService priceService,
        AccountHoldingRepository holdingRepository,
        HistoryService historyService
    ) {
        this.accountRepository = accountRepository;
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.priceService = priceService;
        this.holdingRepository = holdingRepository;
        this.historyService = historyService;
    }

    public DashboardResponse getDashboard(Long memberId, String range) {
        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId);

        // Pre-load all holdings and group by account
        Map<Long, List<AccountHolding>> holdingsByAccount = new HashMap<>();
        for (Account account : accounts) {
            holdingsByAccount.put(account.getId(), holdingRepository.findByAccount_Id(account.getId()));
        }

        // Calculate live total and invested from holdings + cash
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;

        for (Account account : accounts) {
            List<AccountHolding> holdings = holdingsByAccount.get(account.getId());

            BigDecimal accountValue;
            BigDecimal accountInvested;

            if (holdings.isEmpty()) {
                accountValue = priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
                accountInvested = accountValue;
            } else {
                BigDecimal liveValue = BigDecimal.ZERO;
                BigDecimal investedValue = BigDecimal.ZERO;
                for (AccountHolding h : holdings) {
                    BigDecimal qty = h.getQuantity();
                    BigDecimal avgBuy = h.getAverageBuyIn() != null ? h.getAverageBuyIn() : BigDecimal.ZERO;

                    liveValue = liveValue.add(holdingValueEur(h));
                    investedValue = investedValue.add(qty.multiply(avgBuy));
                }
                log.info("getDashboard: account={} holdings={} liveValue={} investedValue={}",
                    account.getId(), holdings.size(), liveValue, investedValue);
                accountValue = liveValue;
                accountInvested = investedValue;
            }

            if (account.getType() == AccountType.LOAN) {
                totalLiabilities = totalLiabilities.add(accountValue);
            } else {
                totalAssets = totalAssets.add(accountValue);
                totalInvested = totalInvested.add(accountInvested);
            }
        }

        BigDecimal totalNetWorth = totalAssets.subtract(totalLiabilities);

        log.info("getDashboard: totalAssets={}, totalLiabilities={}, totalNetWorth={}, totalInvested={}, pnl={}",
            totalAssets, totalLiabilities, totalNetWorth, totalInvested, totalNetWorth.subtract(totalInvested));

        // Build history using shared HistoryService
        List<Long> allAccountIds = accounts.stream().map(Account::getId).toList();
        int months = switch (range != null ? range : "1Y") {
            case "7D", "1M" -> 1;
            case "3M" -> 3;
            case "YTD" -> LocalDate.now().getMonthValue();
            case "ALL" -> 1200;
            default -> 12;
        };
        List<NetWorthPoint> updatedHistory = historyService.buildHistory(allAccountIds, months, memberId);

        List<DistributionItem> distribution = buildDistribution(accounts, totalNetWorth, holdingsByAccount, false);
        List<DistributionItem> liabilities = buildDistribution(accounts, totalNetWorth, holdingsByAccount, true);

        List<GoalProgressResponse> goals = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(goalService::toProgressResponse)
            .toList();

        return new DashboardResponse(totalNetWorth, totalLiabilities, updatedHistory, distribution, liabilities, goals);
    }

    private List<DistributionItem> buildDistribution(List<Account> accounts, BigDecimal totalNetWorth,
                                                       Map<Long, List<AccountHolding>> holdingsByAccount,
                                                       boolean liabilitiesOnly) {
        List<DistributionItem> items = new ArrayList<>();

        for (Account account : accounts) {
            boolean isLoan = account.getType() == AccountType.LOAN;
            if (liabilitiesOnly != isLoan) continue;

            List<AccountHolding> holdings = holdingsByAccount.getOrDefault(account.getId(), List.of());
            BigDecimal balanceEur;
            if (holdings.isEmpty()) {
                balanceEur = priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
            } else {
                balanceEur = BigDecimal.ZERO;
                for (AccountHolding h : holdings) {
                    balanceEur = balanceEur.add(holdingValueEur(h));
                }
            }

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
                Math.round(percentage * 100.0) / 100.0,
                account.getType().name(),
                !holdings.isEmpty()
            ));
        }

        return items;
    }

    private BigDecimal holdingValueEur(AccountHolding holding) {
        BigDecimal livePrice = holding.getTicker() != null ? priceService.getPriceEur(holding.getTicker()) : null;
        if (livePrice == null) {
            log.warn("No live price for ticker '{}' — holding {} valued at zero until a quote is available",
                holding.getTicker(), holding.getId());
            return BigDecimal.ZERO;
        }
        return holding.getQuantity().multiply(livePrice);
    }
}
