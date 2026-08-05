package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.DegiroPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DegiroAdapter implements DegiroPort {

    private static final Logger log = LoggerFactory.getLogger(DegiroAdapter.class);

    private final WebClient sidecarClient;

    public DegiroAdapter(
        ObjectMapper objectMapper,
        @Value("${app.degiro-auth.url:http://degiro-auth:8001}") String degiroAuthUrl
    ) {
        this.sidecarClient = WebClient.builder()
            .baseUrl(degiroAuthUrl)
            .build();
    }

    @Override
    public InitiateResult initiateAuth(String username, String password) {
        log.info("Delegating DEGIRO auth initiation to degiro-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", username, "password", password))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("degiro-auth /initiate failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "DEGIRO authentication failed. Please check your credentials and try again."));
            })
            .timeout(Duration.ofSeconds(30))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the DEGIRO service. Please try again later."));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("DEGIRO did not return a valid session. Please try again.");
        }

        boolean totpRequired = response.path("totpRequired").asBoolean(false);
        if (!totpRequired) {
            String blob = response.path("sessionBlob").asText(null);
            if (blob == null || blob.isBlank()) {
                throw new SyncException("DEGIRO authentication did not complete. Please try again.");
            }
            return new InitiateResult(processId, false, blob);
        }

        return new InitiateResult(processId, true, null);
    }

    @Override
    public String completeAuth(String processId, String code) {
        log.info("Delegating DEGIRO TOTP completion to degiro-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "code", code))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("degiro-auth /complete failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "The verification code is invalid or has expired. Please request a new one."));
            })
            .timeout(Duration.ofSeconds(30))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the DEGIRO service. Please try again later."));

        String blob = response.path("sessionBlob").asText(null);
        if (blob == null || blob.isBlank()) {
            throw new SyncException("DEGIRO verification did not complete. Please try again.");
        }
        return blob;
    }

    @Override
    public DegiroPortfolioData fetchPortfolio(String sessionBlob) {
        log.info("Fetching DEGIRO portfolio via degiro-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/portfolio")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("sessionBlob", sessionBlob))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                if (ex.getStatusCode().value() == 401) {
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                log.error("degiro-auth /portfolio failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Could not fetch your DEGIRO portfolio. Please try again later."));
            })
            .timeout(Duration.ofSeconds(30))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the DEGIRO service. Please try again later."));

        BigDecimal cashEur = BigDecimal.valueOf(response.path("cashEur").asDouble(0));

        List<DegiroPosition> positions = new ArrayList<>();
        for (JsonNode p : response.path("positions")) {
            positions.add(new DegiroPosition(
                nullIfBlank(p.path("isin").asText()),
                p.path("symbol").asText(),
                p.path("name").asText(),
                BigDecimal.valueOf(p.path("quantity").asDouble(0)),
                BigDecimal.valueOf(p.path("buyingPrice").asDouble(0)),
                BigDecimal.valueOf(p.path("currentPrice").asDouble(0))
            ));
        }

        log.info("DEGIRO: fetched portfolio with {} positions", positions.size());
        return new DegiroPortfolioData(cashEur, positions);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
