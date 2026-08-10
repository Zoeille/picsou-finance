package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
    Long id,
    String name,
    AccountType type,
    String provider,
    String currency,
    BigDecimal currentBalance,
    BigDecimal currentBalanceEur,
    BigDecimal cashBalance,
    Instant lastSyncedAt,
    boolean isManual,
    String color,
    String ticker,
    String logoUrl,
    String logoKey,
    Instant createdAt,
    RealEstateMetadataResponse realEstate,
    DebtResponse debt
) {
    public static AccountResponse from(Account a, BigDecimal balanceEur) {
        return new AccountResponse(
            a.getId(),
            a.getName(),
            a.getType(),
            a.getProvider(),
            a.getCurrency(),
            a.getCurrentBalance(),
            balanceEur,
            a.getCashBalance(),
            a.getLastSyncedAt(),
            a.isManual(),
            a.getColor(),
            a.getTicker(),
            a.getLogoUrl(),
            a.getLogoKey(),
            a.getCreatedAt(),
            null,
            null
        );
    }

    public AccountResponse withRealEstate(RealEstateMetadataResponse realEstate) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey, createdAt,
            realEstate, debt);
    }

    public AccountResponse withDebt(DebtResponse debt) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey, createdAt,
            realEstate, debt);
    }
}
