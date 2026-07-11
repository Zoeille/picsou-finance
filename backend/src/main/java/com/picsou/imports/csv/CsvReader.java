package com.picsou.imports.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free RFC-4180 CSV reader with a configurable delimiter. Handles
 * quoted fields (embedded delimiters, CR/LF inside quotes, and doubled {@code ""} escapes),
 * a leading UTF-8 BOM, and both CRLF and LF line endings. Fully-blank lines are skipped,
 * but a row of empty cells ({@code ,,}) is preserved. Symmetric with the hand-rolled
 * {@code CsvWriter} used for GDPR export, so an exported file round-trips.
 */
public final class CsvReader {

    private static final char BOM = '\uFEFF';

    private CsvReader() {
    }

    public static List<List<String>> parse(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        // Strip a leading UTF-8 BOM if present.
        if (content.charAt(0) == BOM) {
            content = content.substring(1);
        }

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();

        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
            } else {
                field.append(c);
                i++;
            }
        }

        // Finalize the trailing field/row (files not ending in a newline).
        row.add(field.toString());
        addRow(rows, row);
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        // Skip a fully-blank line (a single empty field); keep intentional empty cells.
        if (row.size() == 1 && row.get(0).isEmpty()) {
            return;
        }
        rows.add(row);
    }
}
