package com.picsou.controller;

import com.picsou.service.SyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncControllerTest {

    @Mock SyncService syncService;
    @Mock UserContext userContext;
    @Mock HttpServletRequest httpRequest;

    private SyncController controller(Map<String, Bucket> syncBuckets) {
        return new SyncController(syncService, userContext, syncBuckets);
    }

    @Test
    void listCountries_returnsServiceResult() {
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(syncService.listCountries()).thenReturn(List.of("FR", "DE", "EE"));

        ResponseEntity<?> response = controller(new ConcurrentHashMap<>()).listCountries(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of("FR", "DE", "EE"));
    }

    @Test
    void listCountries_rateLimitExceeded_returns429_withoutCallingService() {
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        // Pre-seed an already-exhausted, single-token bucket for this IP.
        Bucket oneTokenBucket = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofMinutes(1)).build())
            .build();
        oneTokenBucket.tryConsume(1);
        buckets.put("10.0.0.1", oneTokenBucket);
        SyncController ctrl = controller(buckets);

        ResponseEntity<?> response = ctrl.listCountries(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getDetail()).contains("Too many sync requests");
        verifyNoInteractions(syncService);
    }
}
