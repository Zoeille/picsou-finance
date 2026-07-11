package com.picsou.dto;

import java.util.List;

/**
 * Preview returned by the first phase of a CSV transaction import. Carries a {@code fileToken}
 * (bound to the target account, ~30-min TTL) the client echoes back to commit, the detected
 * column headers, a handful of sample rows, the total data-row count, the detected dialect,
 * and a best-guess column mapping — all overridable by the user before the import runs.
 */
public record TransactionImportPreviewResponse(
    String fileToken,
    List<String> detectedColumns,
    List<List<String>> sampleRows,
    int totalRows,
    boolean hasHeaderRow,
    CsvDialectDto dialect,
    ColumnMappingDto suggestedMapping
) {}
