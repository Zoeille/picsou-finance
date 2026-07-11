package com.picsou.imports.csv;

/**
 * The dialect needed to interpret a CSV: the field delimiter, how decimals are written,
 * and the date pattern. Detected as a best guess by {@link CsvDialectDetector} and
 * overridable by the user in the import wizard before the final import runs.
 */
public record CsvDialect(char delimiter, DecimalStyle decimal, String dateFormat) {
}
