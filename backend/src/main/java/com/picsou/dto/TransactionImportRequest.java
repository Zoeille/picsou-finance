package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Commit request for the second phase of a CSV transaction import. Echoes the {@code fileToken}
 * from the preview plus the (possibly user-adjusted) column mapping and dialect. {@code hasHeaderRow}
 * tells the importer to skip the first data row; {@code feesIncludedInAmount} says the mapped amount
 * column already nets fees (so the unit price is derived accordingly); {@code sideValueMap} maps raw
 * cell text (e.g. "Achat") to {@code "BUY"}/{@code "SELL"} when the side column uses custom labels.
 */
public record TransactionImportRequest(
    @NotBlank String fileToken,
    @NotNull ColumnMappingDto mapping,
    @NotNull CsvDialectDto dialect,
    boolean hasHeaderRow,
    boolean feesIncludedInAmount,
    Map<String, String> sideValueMap
) {}
