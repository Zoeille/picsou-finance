package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Enable Banking Bank Account Data API connector.
 * https://enablebanking.com/docs/api/reference/
 *
 * Auth: JWT signed with your RSA private key (RS256).
 * Equivalent to GoCardless requisitions → Enable Banking sessions.
 */
@Component
public class EnableBankingBankConnector implements BankConnectorPort {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingBankConnector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final String applicationId;
    private final String keyId;
    private final String redirectUri;
    private final PrivateKey privateKey;
    private final WebClient webClient;

    public EnableBankingBankConnector(
        @Value("${app.enablebanking.application-id:}") String applicationId,
        @Value("${app.enablebanking.key-id:}") String keyId,
        @Value("${app.enablebanking.redirect-uri:http://localhost:5173/sync/callback}") String redirectUri,
        @Value("${app.enablebanking.private-key:}") String privateKeyPem,
        @Value("${app.enablebanking.private-key-path:}") String privateKeyPath,
        @Value("${app.enablebanking.base-url:https://api.enablebanking.com}") String baseUrl
    ) {
        this.applicationId = applicationId;
        this.keyId = keyId;
        this.redirectUri = redirectUri;
        this.privateKey = resolvePrivateKey(privateKeyPem, privateKeyPath);
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    // ─── BankConnectorPort ────────────────────────────────────────────────────

    @Override
    public InitiateResult initiateConnection(String institutionId) {
        // institutionId format: "BankName::FR" (name::country)
        String[] parts = institutionId.split("::");
        String bankName = parts[0];
        String country = parts.length > 1 ? parts[1] : "FR";

        var body = Map.of(
            "access", Map.of("valid_until", Instant.now().plus(90, ChronoUnit.DAYS).toString()),
            "aspsp", Map.of("name", bankName, "country", country),
            "state", applicationId + "_" + System.currentTimeMillis(),
            "redirect_url", redirectUri,
            "psu_type", "personal"
        );

        AuthStartResponse auth = webClient.post()
            .uri("/auth")
            .header("Authorization", "Bearer " + buildJwt())
            .bodyValue(body)
            .retrieve()
            .bodyToMono(AuthStartResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Enable Banking auth failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Enable Banking auth error: " + ex.getMessage(), ex))
            .block();

        if (auth == null || auth.url() == null) {
            throw new SyncException("Empty response from Enable Banking /auth");
        }

        log.info("Enable Banking auth initiated, authorization_id={}", auth.authorizationId());
        return new InitiateResult(auth.authorizationId(), auth.url());
    }

    @Override
    public String exchangeCode(String oauthCode) {
        log.info("Exchanging OAuth code for Enable Banking session");
        SessionCreateResponse session = webClient.post()
            .uri("/sessions")
            .header("Authorization", "Bearer " + buildJwt())
            .bodyValue(Map.of("code", oauthCode))
            .retrieve()
            .bodyToMono(SessionCreateResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Enable Banking code exchange failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Enable Banking code exchange error: " + ex.getMessage(), ex))
            .block();

        if (session == null || session.sessionId() == null) {
            throw new SyncException("Empty session response from Enable Banking /sessions");
        }

        log.info("Enable Banking session created: {}", session.sessionId());
        return session.sessionId();
    }

    @Override
    public List<AccountData> fetchBalances(String sessionId) {
        List<String> accounts = fetchSessionAccountsWithRetry(sessionId);
        return accounts.stream()
            .map(accountId -> fetchAccountData(accountId))
            .toList();
    }

    /**
     * Polls GET /sessions/{id} until accounts are populated.
     * Enable Banking links accounts asynchronously after OAuth — typically takes a few seconds.
     */
    private List<String> fetchSessionAccountsWithRetry(String sessionId) {
        int maxAttempts = 8;
        int delayMs = 3_000;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            SessionResponse session = webClient.get()
                .uri("/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + buildJwt())
                .retrieve()
                .bodyToMono(SessionResponse.class)
                .timeout(TIMEOUT)
                .onErrorMap(WebClientResponseException.class,
                    ex -> new SyncException("Failed to fetch session: " + ex.getResponseBodyAsString(), ex))
                .block();

            if (session != null && session.accounts() != null && !session.accounts().isEmpty()) {
                log.info("Session {} has {} accounts (attempt {}, status={})",
                    sessionId, session.accounts().size(), attempt, session.status());
                return session.accounts();
            }

            log.info("Session {} has no accounts yet (attempt {}/{}, status={}), retrying in {}s…",
                sessionId, attempt, maxAttempts,
                session != null ? session.status() : "null",
                delayMs / 1000);

            if (attempt < maxAttempts) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        throw new SyncException(
            "Session " + sessionId + " has no linked accounts after " + maxAttempts + " attempts. " +
            "The bank authorization may not have completed — please retry the sync."
        );
    }

    @Override
    public List<InstitutionData> searchInstitutions(String query, String country) {
        log.info("Searching institutions: query='{}' country='{}'", query, country);
        AspspsResponse response = webClient.get()
            .uri(uriBuilder -> {
                var b = uriBuilder.path("/aspsps").queryParam("psu_type", "personal");
                if (country != null && !country.isBlank()) b.queryParam("country", country);
                return b.build();
            })
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(AspspsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Failed to fetch institutions: [" + ex.getStatusCode() + "] " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Failed to fetch institutions: " + ex.getMessage(), ex))
            .block();

        int count = (response != null && response.aspsps() != null) ? response.aspsps().size() : 0;
        log.info("Enable Banking returned {} ASPSPs", count);

        List<AspspResponse> aspsps = (response != null && response.aspsps() != null) ? response.aspsps() : List.of();

        if (aspsps == null) return List.of();

        String q = query != null ? query.toLowerCase() : "";
        return aspsps.stream()
            .filter(a -> q.isEmpty() || (a.name() != null && a.name().toLowerCase().contains(q)))
            .map(a -> new InstitutionData(
                a.name() + "::" + (a.country() != null ? a.country() : country != null ? country : ""),
                a.name(),
                a.bic(),
                a.logo(),
                a.country() != null ? a.country() : country
            ))
            .limit(20)
            .toList();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private AccountData fetchAccountData(String accountId) {
        BalancesResponse balances = webClient.get()
            .uri("/accounts/{id}/balances", accountId)
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(BalancesResponse.class)
            .timeout(TIMEOUT)
            .block();

        AccountDetailsResponse details = webClient.get()
            .uri("/accounts/{id}/details", accountId)
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(AccountDetailsResponse.class)
            .timeout(TIMEOUT)
            .block();

        BigDecimal balance = BigDecimal.ZERO;
        String currency = "EUR";
        String name = "Account";
        String iban = null;

        log.info("Balances for account {}: {}", accountId, balances);
        if (balances != null && balances.balances() != null && !balances.balances().isEmpty()) {
            var b = balances.balances().stream()
                .filter(bl -> "closingBooked".equals(bl.balanceType()) || "expected".equals(bl.balanceType()))
                .findFirst()
                .orElse(balances.balances().get(0));
            if (b.balanceAmount() != null) {
                balance = new BigDecimal(b.balanceAmount().amount());
                currency = b.balanceAmount().currency();
            }
        }

        if (details != null && details.account() != null) {
            name = details.account().name() != null ? details.account().name() :
                   details.account().product() != null ? details.account().product() : "Account";
            iban = details.account().iban();
        }

        return new AccountData(accountId, name, iban, currency, balance);
    }

    /**
     * Build a short-lived RS256 JWT to authenticate API calls.
     * https://enablebanking.com/docs/api/reference/#section/Authentication
     */
    String buildJwt() {
        if (privateKey == null || applicationId.isBlank()) {
            throw new SyncException(
                "Enable Banking is not configured. Set ENABLEBANKING_APPLICATION_ID, " +
                "ENABLEBANKING_KEY_ID and ENABLEBANKING_PRIVATE_KEY in your .env file. " +
                "Register free at https://enablebanking.com/"
            );
        }

        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(keyId).and()
            .issuer(applicationId)
            .claim("aud", "api.enablebanking.com")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    private static PrivateKey resolvePrivateKey(String pem, String path) {
        if (!path.isBlank()) {
            try {
                return parsePrivateKey(Files.readString(Path.of(path)));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read private key from " + path, ex);
            }
        }
        return pem.isBlank() ? null : parsePrivateKey(pem);
    }

    /**
     * Parse a PKCS8 PEM private key string.
     * Supports both "BEGIN PRIVATE KEY" (PKCS8) format.
     * The value can have literal \n in the env var — both are handled.
     */
    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String cleaned = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

            byte[] der = Base64.getDecoder().decode(cleaned);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to parse ENABLEBANKING_PRIVATE_KEY. Must be PKCS8 PEM format (BEGIN PRIVATE KEY). " +
                "Generate with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem",
                ex
            );
        }
    }

    // ─── Enable Banking API response types ───────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthStartResponse(
        String url,
        @com.fasterxml.jackson.annotation.JsonProperty("authorization_id") String authorizationId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionCreateResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId,
        String url,
        String status,
        List<String> accounts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspsResponse(List<AspspResponse> aspsps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspResponse(String name, String bic, String logo, String country) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalancesResponse(List<BalanceItem> balances) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceItem(
            @com.fasterxml.jackson.annotation.JsonProperty("balance_amount") BalanceAmount balanceAmount,
            @com.fasterxml.jackson.annotation.JsonProperty("balance_type") String balanceType
        ) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceAmount(String amount, String currency) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountDetailsResponse(AccountDetail account) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AccountDetail(
            String iban,
            String name,
            String product,
            @com.fasterxml.jackson.annotation.JsonProperty("cash_account_type") String cashAccountType
        ) {}
    }
}
