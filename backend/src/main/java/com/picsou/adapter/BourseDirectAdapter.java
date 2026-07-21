package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.BourseDirectPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class BourseDirectAdapter implements BourseDirectPort {
    private static final Duration PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public BourseDirectAdapter(
        @Value("${app.bourse-direct-auth.url:http://bourse-direct-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(WebClient.builder().baseUrl(url).build(), objectMapper);
    }

    BourseDirectAdapter(WebClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
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
                .timeout(PORTFOLIO_TIMEOUT).block();
            if (response == null || response.length == 0) {
                throw coded(
                    BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
                    "Bourse Direct returned no complete portfolio accounts",
                    null
                );
            }
            return List.of(response);
        } catch (Exception ex) {
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
                .timeout(Duration.ofSeconds(45)).block();
            if (response == null) {
                throw coded(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (Exception ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        Exception ex,
        BourseDirectErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) return sync;
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
        }
        return coded(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private BourseDirectErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            String detail = body.path("detail").asText("");
            return detail.isBlank() ? null : BourseDirectErrorCode.valueOf(detail);
        } catch (Exception ignored) {
            return null;
        }
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
