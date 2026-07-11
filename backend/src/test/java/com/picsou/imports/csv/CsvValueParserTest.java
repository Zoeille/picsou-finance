package com.picsou.imports.csv;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvValueParserTest {

    @Test
    void parsesCommaDecimalWithThousands() {
        assertThat(CsvValueParser.parseDecimal("1.234,56", DecimalStyle.COMMA)).isEqualByComparingTo("1234.56");
        assertThat(CsvValueParser.parseDecimal("1 234,56", DecimalStyle.COMMA)).isEqualByComparingTo("1234.56");
        assertThat(CsvValueParser.parseDecimal("-12,5", DecimalStyle.COMMA)).isEqualByComparingTo("-12.5");
    }

    @Test
    void parsesDotDecimalWithThousands() {
        assertThat(CsvValueParser.parseDecimal("1,234.56", DecimalStyle.DOT)).isEqualByComparingTo("1234.56");
        assertThat(CsvValueParser.parseDecimal("1234.56", DecimalStyle.DOT)).isEqualByComparingTo("1234.56");
        assertThat(CsvValueParser.parseDecimal("+7.5", DecimalStyle.DOT)).isEqualByComparingTo("7.5");
    }

    @Test
    void stripsCurrencySymbols() {
        assertThat(CsvValueParser.parseDecimal("85,20 €", DecimalStyle.COMMA)).isEqualByComparingTo("85.20");
    }

    @Test
    void blankOrNull_returnsNull() {
        assertThat(CsvValueParser.parseDecimal(null, DecimalStyle.DOT)).isNull();
        assertThat(CsvValueParser.parseDecimal("   ", DecimalStyle.DOT)).isNull();
    }

    @Test
    void nonNumeric_throws() {
        assertThatThrownBy(() -> CsvValueParser.parseDecimal("abc", DecimalStyle.DOT))
            .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void parsesDatesByPattern() {
        assertThat(CsvValueParser.parseDate("15/03/2024", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(CsvValueParser.parseDate("2024-03-15", "yyyy-MM-dd")).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    void blankDate_returnsNull() {
        assertThat(CsvValueParser.parseDate("  ", "yyyy-MM-dd")).isNull();
    }

    @Test
    void unparseableDate_throws() {
        assertThatThrownBy(() -> CsvValueParser.parseDate("2024-13-40", "yyyy-MM-dd"))
            .isInstanceOf(DateTimeParseException.class);
    }
}
