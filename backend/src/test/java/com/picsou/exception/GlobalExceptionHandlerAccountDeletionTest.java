package com.picsou.exception;

import com.picsou.service.ReAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAccountDeletionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void reAuthFailure_hasStableCodeAndDoesNotExposeCredentialDetails() {
        ProblemDetail problem = handler.handleReAuthFailed(
            new ReAuthService.ReAuthFailedException("invalid password"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getTitle()).isEqualTo("Re-authentication failed");
        assertThat(problem.getDetail()).isEqualTo("Re-authentication failed");
        assertThat(problem.getProperties()).containsEntry("code", "REAUTH_FAILED");
    }

    @Test
    void lastAdministratorFailure_hasStableCode() {
        ProblemDetail problem = handler.handleLastAdministrator(new LastAdministratorException());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getProperties()).containsEntry("code", "LAST_ADMIN");
    }
}
