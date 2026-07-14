package com.picsou.adapter;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnableBankingBankConnectorTest {

    @Mock EnableBankingConfigProvider configProvider;

    private EnableBankingBankConnector connector() {
        return new EnableBankingBankConnector(configProvider, "https://api.enablebanking.test");
    }

    @Test
    void searchInstitutions_missingPrivateKey_namesTheKey_notGenericNotConfigured() {
        // The reported bug: app-id/key-id present (in DB) but the key file is absent.
        lenient().when(configProvider.applicationId()).thenReturn(Optional.of("app-id"));
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.privateKey()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("private key");
    }

    @Test
    void searchInstitutions_missingApplicationId_namesApplicationId() {
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.applicationId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Application ID");
    }

    @Test
    void parseInstitutionId_nameAndCountry_splitsBoth() {
        var parsed = connector().parseInstitutionId("LHV Pank::EE");

        assertThat(parsed.name()).isEqualTo("LHV Pank");
        assertThat(parsed.country()).isEqualTo("EE");
    }

    @Test
    void parseInstitutionId_blankCountrySegment_fallsBackToDefault() {
        // "Name::" — a trailing blank country must not be silently coerced by
        // split()'s default trailing-empty-removal into "no separator at all".
        var parsed = connector().parseInstitutionId("Some Bank::");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEqualTo("FR");
    }

    @Test
    void parseInstitutionId_noSeparator_fallsBackToDefault() {
        var parsed = connector().parseInstitutionId("Some Bank");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEqualTo("FR");
    }

    @Test
    void parseInstitutionId_nameContainsSeparator_splitsAtLastOccurrence() {
        // A bank name that itself contains "::" must not corrupt the split — the
        // country is always the LAST appended segment.
        var parsed = connector().parseInstitutionId("Foo::Bar::EE");

        assertThat(parsed.name()).isEqualTo("Foo::Bar");
        assertThat(parsed.country()).isEqualTo("EE");
    }
}
