package com.picsou.imports.csv;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort detection of a CSV's {@link CsvDialect} (delimiter, decimal style, date format).
 * The result is only a starting guess — the import wizard lets the user override every field
 * before the final import runs, so detection favors the common cases (French {@code ;} + comma
 * decimals, and the RFC-4180 {@code ,} + dot decimals the GDPR export produces).
 */
public final class CsvDialectDetector {

    private static final char[] DELIMITER_CANDIDATES = {';', ',', '\t'};

    // Priority-ordered date patterns probed against sample cells.
    private static final List<String> DATE_PATTERNS = List.of(
        "yyyy-MM-dd", "dd/MM/yyyy", "dd.MM.yyyy", "MM/dd/yyyy", "yyyy/MM/dd", "dd-MM-yyyy");

    private static final Pattern COMMA_DECIMAL = Pattern.compile("^[+-]?\\d{1,3}(\\.\\d{3})*,\\d+$|^[+-]?\\d+,\\d+$");
    private static final Pattern DOT_DECIMAL = Pattern.compile("^[+-]?\\d{1,3}(,\\d{3})*\\.\\d+$|^[+-]?\\d+\\.\\d+$");

    private static final int SAMPLE_LIMIT = 50;

    private CsvDialectDetector() {
    }

    /** Picks the delimiter that appears most on the first non-empty line; defaults to comma. */
    public static char detectDelimiter(String content) {
        if (content == null || content.isEmpty()) {
            return ',';
        }
        String firstLine = content;
        int nl = content.indexOf('\n');
        if (nl >= 0) {
            firstLine = content.substring(0, nl);
        }
        char best = ',';
        int bestCount = 0;
        for (char d : DELIMITER_CANDIDATES) {
            int count = 0;
            for (int i = 0; i < firstLine.length(); i++) {
                if (firstLine.charAt(i) == d) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = d;
            }
        }
        return best;
    }

    /** Classifies decimal style by counting comma- vs dot-decimal cells; defaults to dot. */
    public static DecimalStyle detectDecimal(List<List<String>> rows) {
        int comma = 0;
        int dot = 0;
        int seen = 0;
        for (List<String> row : rows) {
            if (seen >= SAMPLE_LIMIT) {
                break;
            }
            seen++;
            for (String cell : row) {
                String c = cell == null ? "" : cell.trim();
                if (COMMA_DECIMAL.matcher(c).matches()) {
                    comma++;
                } else if (DOT_DECIMAL.matcher(c).matches()) {
                    dot++;
                }
            }
        }
        return comma > dot ? DecimalStyle.COMMA : DecimalStyle.DOT;
    }

    /** Returns the date pattern that parses the most sample cells; defaults to {@code yyyy-MM-dd}. */
    public static String detectDateFormat(List<List<String>> rows) {
        String best = DATE_PATTERNS.get(0);
        int bestCount = 0;
        for (String pattern : DATE_PATTERNS) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
            int count = 0;
            int seen = 0;
            for (List<String> row : rows) {
                if (seen >= SAMPLE_LIMIT) {
                    break;
                }
                seen++;
                for (String cell : row) {
                    String c = cell == null ? "" : cell.trim();
                    if (c.isEmpty()) {
                        continue;
                    }
                    try {
                        LocalDate.parse(c, fmt);
                        count++;
                    } catch (RuntimeException ignored) {
                        // not a date in this pattern
                    }
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = pattern;
            }
        }
        return best;
    }
}
