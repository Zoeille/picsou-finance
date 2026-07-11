package com.picsou.dto;

import java.util.List;

/**
 * Result of a committed CSV transaction import: how many rows were imported, how many were
 * skipped, and a per-row error list for the skipped ones (1-based row numbers, user-safe messages).
 */
public record TransactionImportResultResponse(
    int imported,
    int skipped,
    List<RowError> errors
) {
    public record RowError(int rowNumber, String message) {}
}
