package com.picsou.imports.csv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReaderTest {

    @Test
    void parsesCommaDelimited() {
        List<List<String>> rows = CsvReader.parse("a,b,c\n1,2,3\n", ',');
        assertThat(rows).containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    }

    @Test
    void parsesSemicolonDelimited() {
        List<List<String>> rows = CsvReader.parse("a;b;c\n1;2;3", ';');
        assertThat(rows).containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    }

    @Test
    void parsesTabDelimited() {
        List<List<String>> rows = CsvReader.parse("a\tb\n1\t2", '\t');
        assertThat(rows).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void quotedFieldMayContainTheDelimiter() {
        List<List<String>> rows = CsvReader.parse("\"Lynch, Peter\",100", ',');
        assertThat(rows).containsExactly(List.of("Lynch, Peter", "100"));
    }

    @Test
    void doubledQuotesAreUnescaped() {
        List<List<String>> rows = CsvReader.parse("\"a \"\"quoted\"\" word\",b", ',');
        assertThat(rows).containsExactly(List.of("a \"quoted\" word", "b"));
    }

    @Test
    void quotedFieldMayContainNewline() {
        List<List<String>> rows = CsvReader.parse("\"line1\nline2\",b", ',');
        assertThat(rows).containsExactly(List.of("line1\nline2", "b"));
    }

    @Test
    void stripsLeadingBom() {
        List<List<String>> rows = CsvReader.parse("\uFEFFa,b", ',');
        assertThat(rows).containsExactly(List.of("a", "b"));
    }

    @Test
    void skipsBlankLinesButKeepsEmptyCells() {
        List<List<String>> rows = CsvReader.parse("a,b\n\n,,\n1,2\n", ',');
        assertThat(rows).containsExactly(
            List.of("a", "b"),
            List.of("", "", ""),
            List.of("1", "2"));
    }

    @Test
    void handlesCrlfLineEndings() {
        List<List<String>> rows = CsvReader.parse("a,b\r\n1,2\r\n", ',');
        assertThat(rows).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }
}
