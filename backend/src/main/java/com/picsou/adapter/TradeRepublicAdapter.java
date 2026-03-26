package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.TradeRepublicPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for Trade Republic's unofficial API.
 *
 * Auth (HTTP) is delegated to the tr-auth Python sidecar, which handles
 * the AWS WAF browser challenge that cannot be solved from plain Java HTTP.
 *
 * Data fetching uses the TR WebSocket API directly (no WAF needed).
 * Protocol version: 31. Session token is passed in each subscription payload.
 *
 * WebSocket subscriptions used:
 *   - availableCash  → cash balance (array response)
 *   - portfolioStatus (attempted) → per sub-account breakdown (PEA, CTO, etc.)
 *
 * All raw WebSocket messages are logged at INFO level for debugging.
 */
@Component
public class TradeRepublicAdapter implements TradeRepublicPort {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicAdapter.class);

    private static final String WS_URL     = "wss://api.traderepublic.com/";
    private static final int    WS_VERSION = 31;

    private final WebClient    sidecarClient;
    private final ObjectMapper objectMapper;

    public TradeRepublicAdapter(
        ObjectMapper objectMapper,
        @Value("${app.tr-auth.url:http://tr-auth:8001}") String trAuthUrl
    ) {
        this.objectMapper   = objectMapper;
        this.sidecarClient  = WebClient.builder()
            .baseUrl(trAuthUrl)
            .build();
    }

    // ─── Auth (delegated to Python sidecar) ───────────────────────────────────

    @Override
    public String initiateAuth(String phoneNumber, String pin) {
        log.info("Delegating TR auth initiation to tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("phoneNumber", phoneNumber, "pin", pin))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("tr-auth sidecar /initiate failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Échec de l'authentification Trade Republic : " + ex.getResponseBodyAsString()));
            })
            .timeout(Duration.ofSeconds(60)) // headless browser takes time
            .blockOptional()
            .orElseThrow(() -> new SyncException("Pas de réponse du service d'authentification TR"));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("Trade Republic n'a pas retourné de processId.");
        }
        return processId;
    }

    @Override
    public TrTokens completeAuth(String processId, String tan) {
        log.info("Delegating TR 2FA completion to tr-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "tan", tan))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("tr-auth sidecar /complete failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Code 2FA invalide ou expiré : " + ex.getResponseBodyAsString()));
            })
            .timeout(Duration.ofSeconds(60))
            .blockOptional()
            .orElseThrow(() -> new SyncException("Pas de réponse du service 2FA TR"));

        String sessionToken = response.path("sessionToken").asText(null);
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new SyncException("Trade Republic n'a pas retourné de sessionToken.");
        }
        String refreshToken = response.path("refreshToken").asText(null);
        return new TrTokens(sessionToken, refreshToken);
    }

    @Override
    public TrTokens refreshSession(String refreshToken) {
        log.info("Refreshing TR session via tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", refreshToken))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("tr-auth sidecar /refresh failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException("SESSION_EXPIRED"));
            })
            .timeout(Duration.ofSeconds(15))
            .blockOptional()
            .orElseThrow(() -> new SyncException("SESSION_EXPIRED"));

        String newSession = response.path("sessionToken").asText(null);
        if (newSession == null || newSession.isBlank()) {
            throw new SyncException("SESSION_EXPIRED");
        }
        String newRefresh = response.path("refreshToken").asText(null);
        log.info("TR session refreshed successfully");
        return new TrTokens(newSession, newRefresh != null ? newRefresh : refreshToken);
    }

    // ─── Data (WebSocket, no WAF needed) ──────────────────────────────────────

    @Override
    public List<TrAccountData> fetchAccounts(String sessionToken) {
        log.info("Fetching TR portfolio via WebSocket (protocol v{})", WS_VERSION);

        AtomicReference<List<TrAccountData>> result = new AtomicReference<>();
        AtomicInteger subId = new AtomicInteger(0);

        // Cash response (array) and portfolio response (object)
        AtomicReference<String> cashJson      = new AtomicReference<>();
        AtomicReference<String> portfolioJson = new AtomicReference<>();
        AtomicBoolean authExpired = new AtomicBoolean(false);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.traderepublic.com");

        String connectMsg = buildConnectMessage();

        new ReactorNettyWebSocketClient()
            .execute(URI.create(WS_URL), headers, session ->
                session.send(Mono.just(session.textMessage(connectMsg)))
                    .thenMany(
                        session.receive()
                            .map(msg -> msg.getPayloadAsText())
                            .flatMap(text -> {
                                log.debug("TR WS <-- {}", text.length() > 500 ? text.substring(0, 500) + "…" : text);

                                if ("connected".equals(text.trim())) {
                                    int id1 = subId.incrementAndGet();
                                    int id2 = subId.incrementAndGet();
                                    String subCash      = sub(id1, "availableCash",   sessionToken);
                                    String subPortfolio = sub(id2, "portfolioStatus", sessionToken);
                                    log.debug("TR WS --> {}", subCash);
                                    log.debug("TR WS --> {}", subPortfolio);
                                    return session.send(Mono.just(session.textMessage(subCash)))
                                        .then(session.send(Mono.just(session.textMessage(subPortfolio))))
                                        .thenReturn(text);
                                }

                                // WS message format: "{id} {type} {json}"
                                // e.g. "1 A [...]" — strip id + single-char type
                                String payload = extractWsPayload(text);
                                if (isAuthError(payload)) {
                                    log.warn("TR WS: session expired (AUTHENTICATION_ERROR)");
                                    authExpired.set(true);
                                } else if (text.startsWith("1 ")) {
                                    cashJson.set(payload);
                                } else if (text.startsWith("2 ")) {
                                    portfolioJson.set(payload);
                                } else if (!"connected".equals(text.trim())) {
                                    log.debug("TR WS <-- (unmatched) {}", text.length() > 400 ? text.substring(0, 400) + "…" : text);
                                }

                                return Mono.just(text);
                            })
                            .takeUntil(text -> authExpired.get()
                                    || (cashJson.get() != null && portfolioJson.get() != null))
                            .timeout(Duration.ofSeconds(10))
                            .onErrorReturn("timeout")
                    )
                    .then()
            )
            .timeout(Duration.ofSeconds(30))
            .block();

        if (authExpired.get()) {
            throw new SyncException("SESSION_EXPIRED");
        }

        List<TrAccountData> accounts = new ArrayList<>();

        if (portfolioJson.get() != null) {
            accounts.addAll(parsePortfolioJson(portfolioJson.get()));
        }

        if (cashJson.get() != null && accounts.stream().noneMatch(a -> "tr_cash".equals(a.externalId()))) {
            accounts.addAll(parseCashJson(cashJson.get()));
        }

        if (accounts.isEmpty()) {
            throw new SyncException(
                "Aucune donnée de portfolio reçue de Trade Republic. Consultez les logs backend.");
        }

        log.info("TR portfolio fetched: {} account(s)", accounts.size());
        return accounts;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private boolean isAuthError(String payload) {
        return payload != null && payload.contains("AUTHENTICATION_ERROR");
    }

    /** Strips "{id} {type} " prefix from a WS message, returning just the JSON payload. */
    private String extractWsPayload(String text) {
        // Format: "1 A {...}" → skip past first space (id), then past second space (type char)
        int first = text.indexOf(' ');
        if (first < 0) return text;
        int second = text.indexOf(' ', first + 1);
        if (second < 0) return text.substring(first + 1);
        return text.substring(second + 1);
    }

    private String buildConnectMessage() {
        try {
            Map<String, Object> payload = Map.of(
                "locale",          "fr",
                "platformId",      "webtrading",
                "platformVersion", "chrome - 125.0.0",
                "clientId",        "app.traderepublic.com",
                "clientVersion",   "3.151.3"
            );
            return "connect " + WS_VERSION + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build TR connect message: " + ex.getMessage());
        }
    }

    private String sub(int id, String type, String token) {
        try {
            return "sub " + id + " " + objectMapper.writeValueAsString(Map.of("type", type, "token", token));
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage());
        }
    }

    /**
     * Parses `availableCash` response (JSON array).
     * Each element may represent a sub-account balance.
     * Raw structure logged at INFO for debugging since format is undocumented.
     */
    private List<TrAccountData> parseCashJson(String json) {
        log.debug("TR availableCash raw: {}", json);
        List<TrAccountData> accounts = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);

            // Find JSON array in response (may be wrapped)
            JsonNode array = root.isArray() ? root : root.path("availableCash");
            if (array.isMissingNode()) array = root;

            if (array.isArray()) {
                for (JsonNode item : array) {
                    BigDecimal value = extractValue(item);
                    if (value.compareTo(BigDecimal.ZERO) >= 0) {
                        accounts.add(new TrAccountData("tr_cash", "TR Cash", AccountType.CHECKING, value));
                        break; // take first entry
                    }
                }
            } else if (array.isObject()) {
                BigDecimal value = extractValue(array);
                if (value.compareTo(BigDecimal.ZERO) >= 0) {
                    accounts.add(new TrAccountData("tr_cash", "TR Cash", AccountType.CHECKING, value));
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse TR availableCash: {}", json, ex);
        }
        return accounts;
    }

    /**
     * Parses `portfolioStatus` response.
     * Expected shape (best-effort — structure varies by TR version):
     * { "portfolioStatus": { "netValue": {...}, "subPortfolios": [...], "cashAccount": {...} } }
     */
    private List<TrAccountData> parsePortfolioJson(String json) {
        log.debug("TR portfolioStatus raw: {}", json);
        List<TrAccountData> accounts = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.has("portfolioStatus") ? root.get("portfolioStatus") : root;

            JsonNode subs = data.path("subPortfolios");
            if (!subs.isMissingNode() && subs.isArray()) {
                for (JsonNode sub : subs) {
                    String type  = sub.path("type").asText("");
                    BigDecimal v = extractValue(sub.path("netValue"));
                    if (v.compareTo(BigDecimal.ZERO) > 0) {
                        accounts.add(new TrAccountData(
                            "tr_" + type.toLowerCase(), labelFor(type), accountTypeFor(type), v));
                    }
                }
            }

            JsonNode cash = data.path("cashAccount");
            if (!cash.isMissingNode()) {
                BigDecimal v = extractValue(cash.path("netValue"));
                if (v.compareTo(BigDecimal.ZERO) >= 0) {
                    accounts.add(new TrAccountData("tr_cash", "TR Cash", AccountType.CHECKING, v));
                }
            }

            if (accounts.isEmpty()) {
                BigDecimal total = extractValue(data.path("netValue"));
                if (total.compareTo(BigDecimal.ZERO) > 0) {
                    log.warn("TR: sub-portfolios not found, using total net value as single account");
                    accounts.add(new TrAccountData("tr_total", "Trade Republic", AccountType.COMPTE_TITRES, total));
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse TR portfolioStatus: {}", json, ex);
        }
        return accounts;
    }

    private BigDecimal extractValue(JsonNode node) {
        if (node == null || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.has("value"))   return new BigDecimal(node.get("value").asText("0"));
        if (node.has("amount"))  return new BigDecimal(node.get("amount").asText("0"));
        if (node.isNumber())     return node.decimalValue();
        return BigDecimal.ZERO;
    }

    private String labelFor(String t) {
        return switch (t.toUpperCase()) {
            case "PEA"          -> "TR PEA";
            case "SECURITIES"   -> "TR CTO";
            case "CRYPTO"       -> "TR Crypto";
            case "SAVINGS_PLAN" -> "TR Plan d'épargne";
            default             -> "TR " + t;
        };
    }

    private AccountType accountTypeFor(String t) {
        return switch (t.toUpperCase()) {
            case "PEA"          -> AccountType.PEA;
            case "CRYPTO"       -> AccountType.CRYPTO;
            case "SAVINGS_PLAN" -> AccountType.SAVINGS;
            default             -> AccountType.COMPTE_TITRES;
        };
    }
}
