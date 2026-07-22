package com.picsou.controller;

import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.service.BourseDirectSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BourseDirectControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock BourseDirectSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private BourseDirectController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new BourseDirectController(service, userContext, authBuckets);
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesAuthenticationToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new BourseDirectSyncService.AuthInitResponse("process", true, "OTP");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiate(
            new BourseDirectController.InitiateRequest("login", "password"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("login", "password", MEMBER_ID))
            .thenReturn(new BourseDirectSyncService.AuthInitResponse("process", true, "OTP"));

        controller.initiate(new BourseDirectController.InitiateRequest("login", "password"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void syncReturnsAcceptedAndTheObservableQueueStatus() {
        var queued = status(BourseDirectSyncStatus.QUEUED);
        when(service.queueSync(MEMBER_ID)).thenReturn(queued);

        var response = controller.sync();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(queued);
        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndClearUseOnlyTheCurrentMember() {
        var success = status(BourseDirectSyncStatus.SUCCESS);
        when(service.getStatus(MEMBER_ID)).thenReturn(success);

        assertThat(controller.status()).isSameAs(success);
        assertThat(controller.clear().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private BourseDirectSyncService.SessionStatusResponse status(BourseDirectSyncStatus syncStatus) {
        return new BourseDirectSyncService.SessionStatusResponse(
            true, null, syncStatus, null, null, null
        );
    }
}
