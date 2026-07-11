package com.picsou.dto;

import com.picsou.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
    @NotNull LocalDate date,
    @NotBlank String description,
    @NotNull BigDecimal amount,
    TransactionType txType,
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal pricePerUnit,
    String currency,
    BigDecimal fees
) {
    /** Backwards-compatible constructor for callers that do not specify per-trade fees. */
    public TransactionRequest(
        LocalDate date, String description, BigDecimal amount, TransactionType txType,
        String ticker, String name, BigDecimal quantity, BigDecimal pricePerUnit, String currency) {
        this(date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, null);
    }
}
