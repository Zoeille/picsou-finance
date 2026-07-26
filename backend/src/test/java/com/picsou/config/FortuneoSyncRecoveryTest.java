package com.picsou.config;

import com.picsou.service.FortuneoSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FortuneoSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        FortuneoSyncService syncService = mock(FortuneoSyncService.class);

        new FortuneoSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }
}
