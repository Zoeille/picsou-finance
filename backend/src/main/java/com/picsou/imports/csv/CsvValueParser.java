package com.picsou.imports.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Parses raw CSV cell strings into {@link BigDecimal} amounts and {@link LocalDate} dates
 * according to a {@link CsvDialect}. Money is always {@code BigDecimal} — never a double —
 * so no precision is lost from a broker's decimal formatting.
 */
public final class CsvValueParser {

    private CsvValueParser() {
    }

    /**
     * Parses a decimal written in the given style. Thousands separators and whitespace
     * (including non-breaking / thin spaces) are stripped; the decimal separator is
     * normalized to a dot.
     *
     * @return the parsed value, or {@code null} if the cell is blank.
     * @throws NumberFormatException if the cell is non-blank but not a number.
     */
    public static BigDecimal parseDecimal(String raw, DecimalStyle style) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Drop currency symbols and any whitespace (regular, non-breaking, thin).
        s = s.replace(" ", "").replace("\u00A0", "").replace("\u202F", "");
        s = s.replace("€", "").replace("$", "").replace("£", "");

        if (style == DecimalStyle.COMMA) {
            // French style: dots are thousands separators, comma is the decimal point.
            s = s.replace(".", "").replace(',', '.');
        } else {
            // Dot style: commas are thousands separators.
            s = s.replace(",", "");
        }
        // Tolerate a leading '+'.
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        return new BigDecimal(s);
    }

    /**
     * Parses a date with the given pattern (e.g. {@code dd/MM/yyyy}, {@code yyyy-MM-dd}).
     *
     * @return the parsed date, or {@code null} if the cell is blank.
     * @throws java.time.format.DateTimeParseException if the cell is non-blank but unparseable.
     */
    public static LocalDate parseDate(String raw, String pattern) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern));
    }
}
