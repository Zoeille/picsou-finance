package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.AmundiErrorCode;
import com.picsou.port.AmundiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class AmundiAdapter implements AmundiPort {
    private static final Logger log = LoggerFactory.getLogger(AmundiAdapter.class);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * An app push waits on a human unlocking their phone. The sidecar caps that
     * wait at 120 s, so this has to sit above it or the adapter would time out
     * on a validation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(90);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public AmundiAdapter(
        @Value("${app.amundi-auth.url:http://amundi-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            WebClient.builder().baseUrl(url).build(),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    AmundiAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_POSITIONS_TIMEOUT);
    }

    AmundiAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration positionsTimeout
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return post("/initiate", Map.of("login", login, "password", password), InitiateResult.class,
            authTimeout, "Could not initiate Amundi authentication", AmundiErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the app-push case; the sidecar forbids unknown fields,
        // so the key has to carry an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SessionResponse response = post("/complete", body, SessionResponse.class, validationTimeout,
            "Could not complete Amundi authentication", AmundiErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    @Override
    public List<PlanData> fetchPlans(String sessionState) {
        try {
            PlanData[] response = client.post().uri("/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionState", sessionState))
                .retrieve().bodyToMono(PlanData[].class)
                .timeout(positionsTimeout).block();
            if (response == null || response.length == 0) {
                throw coded(
                    AmundiErrorCode.PORTFOLIO_INCOMPLETE,
                    "Amundi returned no complete savings plan",
                    null
                );
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError("Could not fetch Amundi savings plans", ex, null);
        }
    }

    private <T> T post(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        AmundiErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw coded(AmundiErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        RuntimeException ex,
        AmundiErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(
                AmundiErrorCode.UPSTREAM_UNAVAILABLE,
                "Amundi took too long to respond. Please try again.",
                ex
            );
        }
        if (ex instanceof WebClientResponseException response) {
            AmundiErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(
                    AmundiErrorCode.AUTH_ATTEMPT_EXPIRED,
                    friendlyMessage(AmundiErrorCode.AUTH_ATTEMPT_EXPIRED),
                    ex
                );
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("Amundi sidecar returned status {}", response.getStatusCode().value());
                return coded(
                    AmundiErrorCode.UPSTREAM_UNAVAILABLE,
                    friendlyMessage(AmundiErrorCode.UPSTREAM_UNAVAILABLE),
                    ex
                );
            }
        }
        log.error(message, ex);
        return coded(AmundiErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private AmundiErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "Amundi error response has no textual detail code (status={})",
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            try {
                return AmundiErrorCode.valueOf(detail);
            } catch (IllegalArgumentException ex) {
                log.warn("Amundi sidecar returned unknown error code '{}'", detail);
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse Amundi sidecar error response (status={})",
                response.getStatusCode().value(),
                ex
            );
            return null;
        }
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String friendlyMessage(AmundiErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Amundi rejected the credentials";
            case CAPTCHA_BLOCKED -> "Amundi asked for a captcha Picsou cannot solve";
            case INVALID_OTP -> "Amundi rejected the verification code";
            case APP_VALIDATION_TIMEOUT -> "The Amundi app validation was not confirmed in time";
            case AUTH_ATTEMPT_EXPIRED -> "The Amundi authentication attempt expired";
            case SESSION_EXPIRED -> "The Amundi session expired";
            case PORTFOLIO_INCOMPLETE -> "Amundi returned incomplete savings plans";
            case UPSTREAM_FORMAT_CHANGED -> "The Amundi website format changed";
            case INVALID_DATA -> "Amundi returned invalid savings plan data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Amundi is temporarily unavailable";
        };
    }

    private SyncException coded(AmundiErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private record SessionResponse(String sessionState) {}
}
