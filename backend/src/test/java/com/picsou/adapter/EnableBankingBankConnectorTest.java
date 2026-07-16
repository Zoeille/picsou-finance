package com.picsou.adapter;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
}
