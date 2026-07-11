package com.picsou.imports.csv;

/**
 * How decimal numbers are written in a CSV. Broker exports vary by locale:
 * <ul>
 *   <li>{@code DOT}   — {@code 1,234.56} (dot decimal, optional comma thousands)</li>
 *   <li>{@code COMMA} — {@code 1 234,56} / {@code 1.234,56} (comma decimal, French style)</li>
 * </ul>
 */
public enum DecimalStyle {
    DOT,
    COMMA
}
