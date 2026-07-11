package com.picsou.imports.csv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvDialectDetectorTest {

    @Test
    void detectsSemicolonDelimiter() {
        assertThat(CsvDialectDetector.detectDelimiter("date;side;ticker;qty\n2024-01-01;BUY;AAPL;10")).isEqualTo(';');
    }

    @Test
    void detectsCommaDelimiter() {
        assertThat(CsvDialectDetector.detectDelimiter("date,side,ticker,qty")).isEqualTo(',');
    }

    @Test
    void detectsTabDelimiter() {
        assertThat(CsvDialectDetector.detectDelimiter("date\tside\tticker")).isEqualTo('\t');
    }

    @Test
    void detectsCommaDecimalFromFrenchSample() {
        List<List<String>> rows = List.of(
            List.of("date", "price"),
            List.of("2024-01-01", "1 234,56"),
            List.of("2024-02-01", "85,20"));
        assertThat(CsvDialectDetector.detectDecimal(rows)).isEqualTo(DecimalStyle.COMMA);
    }

    @Test
    void detectsDotDecimalFromExportSample() {
        List<List<String>> rows = List.of(
            List.of("date", "price"),
            List.of("2024-01-01", "1234.56"),
            List.of("2024-02-01", "85.20"));
        assertThat(CsvDialectDetector.detectDecimal(rows)).isEqualTo(DecimalStyle.DOT);
    }

    @Test
    void detectsDayFirstDateFormat() {
        List<List<String>> rows = List.of(
            List.of("date", "x"),
            List.of("15/03/2024", "a"),
            List.of("28/03/2024", "b"));
        assertThat(CsvDialectDetector.detectDateFormat(rows)).isEqualTo("dd/MM/yyyy");
    }

    @Test
    void detectsIsoDateFormat() {
        List<List<String>> rows = List.of(
            List.of("date", "x"),
            List.of("2024-03-15", "a"));
        assertThat(CsvDialectDetector.detectDateFormat(rows)).isEqualTo("yyyy-MM-dd");
    }
}
