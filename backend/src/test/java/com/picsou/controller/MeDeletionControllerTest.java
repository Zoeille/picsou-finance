package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.dto.AccountDeletionResponse;
import com.picsou.dto.DeleteAccountRequest;
import com.picsou.dto.ReAuthDto;
import com.picsou.model.AccountDeletionMode;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.service.FamilyService;
import com.picsou.service.ReAuthService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeDeletionControllerTest {

    @Mock FamilyService familyService;
    @Mock AuthCookieWriter cookieWriter;
    @Mock HttpServletResponse httpRes;

    private Map<String, Bucket> deleteBuckets;
    private MeDeletionController controller;

    @BeforeEach
    void setUp() {
        deleteBuckets = new ConcurrentHashMap<>();
        controller = new MeDeletionController(familyService, cookieWriter, deleteBuckets);
    }

    private AppUser user() {
        FamilyMember member = FamilyMember.builder().id(9L).displayName("Bob").build();
        return AppUser.builder().id(42L).username("bob").member(member).role(UserRole.MEMBER).build();
    }

    @Test
    void deleteOwnAccount_happyPath_reAuthsDeletesAndReturnsCommittedMode() {
        AppUser user = user();
        ReAuthDto reAuth = new ReAuthDto("s3cret", null);
        when(familyService.deleteOwnAccount(42L, reAuth))
            .thenReturn(AccountDeletionMode.DELETE_ACCOUNT);

        ResponseEntity<?> response =
            controller.deleteOwnAccount(user, new DeleteAccountRequest(reAuth), httpRes);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(
            new AccountDeletionResponse(AccountDeletionMode.DELETE_ACCOUNT));
        verify(familyService).deleteOwnAccount(42L, reAuth);
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    @Test
    void deletionImpact_usesAuthenticatedUserId() {
        AppUser user = user();
        when(familyService.previewOwnAccountDeletion(42L))
            .thenReturn(AccountDeletionMode.RESET_LAST_ADMIN);

        AccountDeletionResponse response = controller.deletionImpact(user);

        assertThat(response.mode()).isEqualTo(AccountDeletionMode.RESET_LAST_ADMIN);
        verify(familyService).previewOwnAccountDeletion(42L);
    }

    @Test
    void deleteOwnAccount_rateLimited_returns429WithoutDeleting() {
        AppUser user = user();
        Bucket exhausted = Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(1)
                .refillIntervally(1, Duration.ofDays(1))
                .build())
            .build();
        exhausted.tryConsume(1);
        deleteBuckets.put("42", exhausted);

        ResponseEntity<?> response = controller.deleteOwnAccount(
            user, new DeleteAccountRequest(new ReAuthDto("s3cret", null)), httpRes);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getProperties())
            .containsEntry("code", "ACCOUNT_DELETION_RATE_LIMITED");
        verify(familyService, never()).deleteOwnAccount(any(), any());
        verify(cookieWriter, never()).clearAuthCookies(any());
    }

    @Test
    void deleteOwnAccount_reAuthFails_abortsBeforeDelete() {
        AppUser user = user();
        ReAuthDto reAuth = new ReAuthDto("wrong", null);
        when(familyService.deleteOwnAccount(42L, reAuth))
            .thenThrow(new ReAuthService.ReAuthFailedException("invalid password"));

        assertThatThrownBy(() -> controller.deleteOwnAccount(
            user, new DeleteAccountRequest(reAuth), httpRes))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);

        verify(familyService).deleteOwnAccount(42L, reAuth);
        verify(cookieWriter, never()).clearAuthCookies(any());
    }
}
