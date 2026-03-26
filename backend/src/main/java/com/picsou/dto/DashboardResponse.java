package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
    BigDecimal totalNetWorth,
    List<NetWorthPoint> netWorthHistory,
    List<DistributionItem> distribution,
    List<GoalProgressResponse> goalSummaries
) {
    public record NetWorthPoint(LocalDate date, BigDecimal total) {}

    public record DistributionItem(
        Long accountId,
        String name,
        String color,
        BigDecimal balanceEur,
        double percentage
    ) {}
}
