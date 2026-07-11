package com.picsou.dto;

/**
 * Maps each canonical transaction field to a zero-based CSV column index (or {@code null} if
 * the column is absent). {@code side} is the BUY/SELL column; {@code tickerOrIsin} accepts either
 * a Yahoo ticker or an ISIN (resolved at import time). {@code amount} is optional — when
 * {@code unitPrice} is absent it is used to derive the unit price.
 */
public record ColumnMappingDto(
    Integer date,
    Integer side,
    Integer tickerOrIsin,
    Integer name,
    Integer quantity,
    Integer unitPrice,
    Integer fees,
    Integer currency,
    Integer amount
) {}
