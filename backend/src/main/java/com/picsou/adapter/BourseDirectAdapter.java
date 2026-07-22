package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.BourseDirectPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class BourseDirectAdapter implements BourseDirectPort {
    private static final Logger log = LoggerFactory.getLogger(BourseDirectAdapter.class);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public BourseDirectAdapter(
        @Value("${app.bourse-direct-auth.url:http://bourse-direct-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            WebClient.builder().baseUrl(url).build(),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
        );
    }

    BourseDirectAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_PORTFOLIO_TIMEOUT);
    }

    BourseDirectAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration portfolioTimeout
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return post("/initiate", Map.of("login", login, "password", password), InitiateResult.class,
            "Could not initiate Bourse Direct authentication", BourseDirectErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        SessionResponse response = post("/complete", Map.of("processId", processId, "code", code),
            SessionResponse.class, "Could not complete Bourse Direct authentication", BourseDirectErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        try {
            AccountData[] response = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionState", sessionState))
                .retrieve().bodyToMono(AccountData[].class)
                .timeout(portfolioTimeout).block();
            if (response == null || response.length == 0) {
                throw coded(
                    BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
                    "Bourse Direct returned no complete portfolio accounts",
                    null
                );
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError("Could not fetch Bourse Direct portfolio", ex, null);
        }
    }

    private <T> T post(
        String path,
        Object body,
        Class<T> type,
        String message,
        BourseDirectErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(authTimeout).block();
            if (response == null) {
                throw coded(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        RuntimeException ex,
        BourseDirectErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(
                BourseDirectErrorCode.UPSTREAM_UNAVAILABLE,
                "Bourse Direct took too long to respond. Please try again.",
                ex
            );
        }
        if (ex instanceof WebClientResponseException response) {
            BourseDirectErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(
                    BourseDirectErrorCode.AUTH_ATTEMPT_EXPIRED,
                    friendlyMessage(BourseDirectErrorCode.AUTH_ATTEMPT_EXPIRED),
                    ex
                );
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("Bourse Direct sidecar returned status {}", response.getStatusCode().value());
                return coded(
                    BourseDirectErrorCode.UPSTREAM_UNAVAILABLE,
                    friendlyMessage(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE),
                    ex
                );
            }
        }
        log.error(message, ex);
        return coded(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private BourseDirectErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "Bourse Direct error response has no textual detail code (status={})",
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            try {
                return BourseDirectErrorCode.valueOf(detail);
            } catch (IllegalArgumentException ex) {
                log.warn("Bourse Direct sidecar returned unknown error code '{}'", detail);
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse Bourse Direct sidecar error response (status={})",
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

    private String friendlyMessage(BourseDirectErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Bourse Direct rejected the credentials";
            case INVALID_OTP -> "Bourse Direct rejected the verification code";
            case AUTH_ATTEMPT_EXPIRED -> "The Bourse Direct authentication attempt expired";
            case SESSION_EXPIRED -> "The Bourse Direct session expired";
            case PORTFOLIO_INCOMPLETE -> "Bourse Direct returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The Bourse Direct website format changed";
            case INVALID_DATA -> "Bourse Direct returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Bourse Direct is temporarily unavailable";
        };
    }

    private SyncException coded(BourseDirectErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private record SessionResponse(String sessionState) {}
}
