package com.picsou.dto;

import com.picsou.model.Goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GoalProgressResponse(
    Long id,
    String name,
    BigDecimal targetAmount,
    LocalDate deadline,
    List<AccountResponse> accounts,
    BigDecimal currentTotal,
    BigDecimal percentComplete,
    long monthsLeft,
    BigDecimal monthlyNeeded,
    BigDecimal avgMonthlyContribution,  // null = pas assez de données
    boolean isOnTrack,
    BigDecimal surplus
) {
    public static GoalProgressResponse from(
        Goal goal,
        List<AccountResponse> accounts,
        BigDecimal currentTotal,
        BigDecimal percentComplete,
        long monthsLeft,
        BigDecimal monthlyNeeded,
        BigDecimal avgMonthlyContribution,
        boolean isOnTrack,
        BigDecimal surplus
    ) {
        return new GoalProgressResponse(
            goal.getId(),
            goal.getName(),
            goal.getTargetAmount(),
            goal.getDeadline(),
            accounts,
            currentTotal,
            percentComplete,
            monthsLeft,
            monthlyNeeded,
            avgMonthlyContribution,
            isOnTrack,
            surplus
        );
    }
}
