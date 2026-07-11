package com.picsou.dto;

/**
 * Wire form of a CSV dialect for the import wizard. {@code delimiter} is a single-character
 * string ({@code ","}, {@code ";"}, or a tab), {@code decimal} is {@code "DOT"} or
 * {@code "COMMA"}, and {@code dateFormat} is a {@code java.time} pattern (e.g. {@code dd/MM/yyyy}).
 */
public record CsvDialectDto(
    String delimiter,
    String decimal,
    String dateFormat
) {}
