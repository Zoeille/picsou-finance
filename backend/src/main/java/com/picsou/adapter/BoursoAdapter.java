package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.BoursoPort;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BoursoAdapter implements BoursoPort {

    private static final Logger log = LoggerFactory.getLogger(BoursoAdapter.class);

    private final WebClient    sidecarClient;
    private final ObjectMapper objectMapper;

    public BoursoAdapter(
        ObjectMapper objectMapper,
        @Value("${app.bourso-auth.url:http://bourso-auth:8001}") String boursoAuthUrl
    ) {
        this.objectMapper   = objectMapper;
        this.sidecarClient  = WebClient.builder()
            .baseUrl(boursoAuthUrl)
            .build();
    }

    @Override
    public InitiateResult initiateAuth(String customerId, String password) {
        log.info("Delegating BoursoBank auth initiation to bourso-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("customerId", customerId, "password", password))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("bourso-auth /initiate failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "BoursoBank authentication failed. Please check your credentials and try again."));
            })
            .timeout(Duration.ofSeconds(30))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the BoursoBank service. Please try again later."));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("BoursoBank did not return a valid session. Please try again.");
        }

        boolean mfaRequired = response.path("mfaRequired").asBoolean(false);
        if (!mfaRequired) {
            String cookies = response.path("sessionCookies").asText(null);
            if (cookies == null || cookies.isBlank()) {
                throw new SyncException("BoursoBank authentication did not complete. Please try again.");
            }
            return new InitiateResult(processId, false, null, null, cookies);
        }

        return new InitiateResult(
            processId,
            true,
            response.path("mfaType").asText("UNKNOWN"),
            response.path("contact").asText(""),
            null
        );
    }

    @Override
    public String completeAuth(String processId, String code) {
        log.info("Delegating BoursoBank MFA completion to bourso-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "code", code))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("bourso-auth /complete failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "The verification code is invalid or has expired. Please request a new one."));
            })
            .timeout(Duration.ofSeconds(60))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the BoursoBank service. Please try again later."));

        String cookies = response.path("sessionCookies").asText(null);
        if (cookies == null || cookies.isBlank()) {
            throw new SyncException("BoursoBank verification did not complete. Please try again.");
        }
        return cookies;
    }

    @Override
    public List<BoursoAccountData> fetchAccounts(String sessionCookies) {
        log.info("Fetching BoursoBank accounts via bourso-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("sessionCookies", sessionCookies))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                if (ex.getStatusCode().value() == 401) {
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                log.error("bourso-auth /accounts failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Could not fetch your BoursoBank accounts. Please try again later."));
            })
            .timeout(Duration.ofSeconds(60))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the BoursoBank service. Please try again later."));

        List<BoursoAccountData> result = new ArrayList<>();
        if (!response.isArray()) {
            throw new SyncException("Unexpected response from the BoursoBank service. Please try again later.");
        }

        for (JsonNode acc : response) {
            String externalId = acc.path("id").asText();
            String name       = acc.path("name").asText();
            AccountType type  = parseAccountType(acc.path("type").asText("OTHER"));
            BigDecimal balance = BigDecimal.valueOf(acc.path("balance").asDouble(0));

            List<BoursoPosition> positions = new ArrayList<>();
            for (JsonNode p : acc.path("positions")) {
                positions.add(new BoursoPosition(
                    nullIfBlank(p.path("isin").asText()),
                    p.path("symbol").asText(),
                    p.path("label").asText(),
                    BigDecimal.valueOf(p.path("quantity").asDouble(0)),
                    BigDecimal.valueOf(p.path("buyingPrice").asDouble(0)),
                    BigDecimal.valueOf(p.path("currentPrice").asDouble(0))
                ));
            }

            List<BoursoTransaction> transactions = new ArrayList<>();
            int skippedTransactions = 0;
            for (JsonNode t : acc.path("transactions")) {
                LocalDate txDate;
                try {
                    txDate = LocalDate.parse(t.path("date").asText());
                } catch (DateTimeParseException ex) {
                    skippedTransactions++;
                    continue;
                }
                transactions.add(new BoursoTransaction(
                    txDate,
                    t.path("label").asText(),
                    BigDecimal.valueOf(t.path("amount").asDouble(0)),
                    t.path("category").asText("")
                ));
            }

            if (skippedTransactions > 0) {
                log.warn("BoursoBank account {}: dropped {} transaction(s) with an unparsable date",
                    externalId, skippedTransactions);
            }

            result.add(new BoursoAccountData(externalId, name, type, balance, positions, transactions));
        }

        log.info("BoursoBank: fetched {} accounts", result.size());
        return result;
    }

    private AccountType parseAccountType(String s) {
        try {
            return AccountType.valueOf(s);
        } catch (IllegalArgumentException ex) {
            log.warn("BoursoBank returned unknown account type '{}' — mapping it to OTHER", s);
            return AccountType.OTHER;
        }
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
