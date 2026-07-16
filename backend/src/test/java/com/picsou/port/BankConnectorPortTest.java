package com.picsou.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankConnectorPortTest {

    @Test
    void parseInstitutionId_nameAndCountry_splitsBoth() {
        var parsed = BankConnectorPort.parseInstitutionId("LHV Pank::EE");

        assertThat(parsed.name()).isEqualTo("LHV Pank");
        assertThat(parsed.country()).isEqualTo("EE");
    }

    @Test
    void parseInstitutionId_blankCountrySegment_returnsBlankCountry() {
        // "Name::" — a trailing blank country must not be silently coerced by
        // split()'s default trailing-empty-removal into "no separator at all".
        // Falling back to a concrete default (if any) is the caller's decision.
        var parsed = BankConnectorPort.parseInstitutionId("Some Bank::");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEmpty();
    }

    @Test
    void parseInstitutionId_noSeparator_returnsBlankCountry() {
        var parsed = BankConnectorPort.parseInstitutionId("Some Bank");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEmpty();
    }

    @Test
    void parseInstitutionId_nameContainsSeparator_splitsAtLastOccurrence() {
        // A bank name that itself contains "::" must not corrupt the split — the
        // country is always the LAST appended segment.
        var parsed = BankConnectorPort.parseInstitutionId("Foo::Bar::EE");

        assertThat(parsed.name()).isEqualTo("Foo::Bar");
        assertThat(parsed.country()).isEqualTo("EE");
    }
}
