package com.picsou.dto;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
    Long id,
    LocalDate date,
    String description,
    BigDecimal amount,
    String type,
    String category,
    String nativeCurrency,
    Instant createdAt,
    boolean isManual,
    TransactionType txType,
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal pricePerUnit,
    BigDecimal fees
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
            t.getId(),
            t.getDate(),
            t.getDescription(),
            t.getAmount(),
            t.getType(),
            t.getCategory(),
            t.getNativeCurrency(),
            t.getCreatedAt(),
            t.isManual(),
            t.getTxType(),
            t.getTicker(),
            t.getName(),
            t.getQuantity(),
            t.getPricePerUnit(),
            t.getFees()
        );
    }
}
