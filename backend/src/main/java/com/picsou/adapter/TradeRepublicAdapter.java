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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   - availableCash      → cash balance
 *   - compactPortfolio   → list of positions (instrumentId, netSize, averageBuyIn)
 *   - ticker             → current price per instrument (subscribed dynamically)
 *
 * Portfolio value = sum(ticker.last.price × position.netSize) for each position.
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

        List<String> secAccNos = extractSecAccountNumbers(sessionToken);
        log.info("TR JWT sec accounts: {}", secAccNos);

        AtomicReference<String> cashJson = new AtomicReference<>();
        ConcurrentHashMap<String, JsonNode> positionsByIsin = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, BigDecimal> tickerPrices = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, String> tickerSubToIsin = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, String> portfolioSubIds = new ConcurrentHashMap<>();
        AtomicBoolean authExpired = new AtomicBoolean(false);
        AtomicInteger subIdCounter = new AtomicInteger(0);
        AtomicInteger expectedTickers = new AtomicInteger(-1);
        AtomicInteger receivedTickers = new AtomicInteger(0);
        AtomicInteger receivedPortfolios = new AtomicInteger(0);
        int totalPortfolioSubs = Math.max(secAccNos.size(), 1);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.traderepublic.com");
        String connectMsg = buildConnectMessage();

        new ReactorNettyWebSocketClient()
            .execute(URI.create(WS_URL), headers, session ->
                session.send(Mono.just(session.textMessage(connectMsg)))
                    .thenMany(
                        session.receive()
                            .map(msg -> msg.getPayloadAsText())
                            .concatMap(text -> {
                                log.info("TR WS <-- {}", text.length() > 500
                                        ? text.substring(0, 500) + "…" : text);

                                if ("connected".equals(text.trim())) {
                                    int id1 = subIdCounter.incrementAndGet();
                                    List<String> msgs = new ArrayList<>();
                                    msgs.add(sub(id1, "availableCash", sessionToken));
                                    log.info("TR WS --> sub {} availableCash", id1);

                                    if (secAccNos.isEmpty()) {
                                        int id2 = subIdCounter.incrementAndGet();
                                        portfolioSubIds.put(id2, "default");
                                        msgs.add(sub(id2, "compactPortfolio", sessionToken));
                                        log.info("TR WS --> sub {} compactPortfolio (no secAccNo)", id2);
                                    } else {
                                        for (String accNo : secAccNos) {
                                            int id = subIdCounter.incrementAndGet();
                                            portfolioSubIds.put(id, accNo);
                                            msgs.add(subCompactPortfolio(id, accNo, sessionToken));
                                            log.info("TR WS --> sub {} compactPortfolio secAccNo={}", id, accNo);
                                        }
                                    }

                                    return session.send(
                                        Flux.fromIterable(msgs).map(session::textMessage)
                                    ).thenReturn(text);
                                }

                                int wsId = extractWsId(text);
                                String payload = extractWsPayload(text);

                                if (isAuthError(payload)) {
                                    log.warn("TR WS: session expired (AUTHENTICATION_ERROR)");
                                    authExpired.set(true);
                                    return Mono.just(text);
                                }

                                if (wsId == 1) {
                                    cashJson.set(payload);

                                } else if (portfolioSubIds.containsKey(wsId)) {
                                    String accNo = portfolioSubIds.get(wsId);
                                    receivedPortfolios.incrementAndGet();
                                    log.info("TR compactPortfolio [{}] raw: {}", accNo,
                                             payload.length() > 2000
                                                     ? payload.substring(0, 2000) + "…" : payload);
                                    try {
                                        JsonNode root = objectMapper.readTree(payload);
                                        JsonNode posArray = root.isArray() ? root : root.path("positions");

                                        if (posArray.isArray() && posArray.size() > 0) {
                                            List<String> tickerMsgs = new ArrayList<>();
                                            for (JsonNode pos : posArray) {
                                                String isin = pos.path("instrumentId").asText("");
                                                if (!isin.isEmpty()) {
                                                    positionsByIsin.put(isin, pos);
                                                    int tid = subIdCounter.incrementAndGet();
                                                    tickerSubToIsin.put(tid, isin);
                                                    tickerMsgs.add(subWithId(tid, "ticker",
                                                            isin + ".LSX", sessionToken));
                                                }
                                            }
                                            int prev = expectedTickers.get();
                                            expectedTickers.set((prev < 0 ? 0 : prev) + tickerMsgs.size());
                                            log.info("TR compactPortfolio [{}]: {} positions, subscribing to {} tickers",
                                                     accNo, posArray.size(), tickerMsgs.size());

                                            if (!tickerMsgs.isEmpty()) {
                                                return session.send(
                                                    Flux.fromIterable(tickerMsgs)
                                                        .map(session::textMessage)
                                                ).thenReturn(text);
                                            }
                                        } else {
                                            int prev = expectedTickers.get();
                                            expectedTickers.compareAndSet(-1, 0);
                                            log.info("TR compactPortfolio [{}]: no positions found", accNo);
                                        }
                                    } catch (Exception ex) {
                                        log.error("Failed to parse compactPortfolio [{}]: {}", accNo, payload, ex);
                                        expectedTickers.compareAndSet(-1, 0);
                                    }

                                } else if (tickerSubToIsin.containsKey(wsId)) {
                                    String isin = tickerSubToIsin.get(wsId);
                                    receivedTickers.incrementAndGet();
                                    try {
                                        JsonNode tickerRoot = objectMapper.readTree(payload);
                                        String priceStr = tickerRoot.path("last").path("price").asText(null);
                                        if (priceStr != null) {
                                            tickerPrices.put(isin, new BigDecimal(priceStr));
                                        } else {
                                            log.warn("TR ticker for {} — no last.price in: {}", isin,
                                                     payload.length() > 300 ? payload.substring(0, 300) : payload);
                                        }
                                    } catch (Exception ex) {
                                        log.warn("Failed to parse ticker for {}: {}", isin, payload);
                                    }
                                }

                                return Mono.just(text);
                            })
                            .takeUntil(text -> {
                                if (authExpired.get()) return true;
                                boolean cashDone = cashJson.get() != null;
                                boolean allPortfoliosIn = receivedPortfolios.get() >= totalPortfolioSubs;
                                int exp = expectedTickers.get();
                                boolean tickersDone = allPortfoliosIn
                                        && exp >= 0
                                        && receivedTickers.get() >= exp;
                                return cashDone && tickersDone;
                            })
                            .timeout(Duration.ofSeconds(30))
                            .onErrorReturn("timeout")
                    )
                    .then()
            )
            .timeout(Duration.ofSeconds(45))
            .block();

        if (authExpired.get()) {
            throw new SyncException("SESSION_EXPIRED");
        }

        // ─── Build accounts from collected data ──────────────────────────────

        List<TrAccountData> accounts = new ArrayList<>();

        BigDecimal totalPortfolioValue = BigDecimal.ZERO;
        int priced = 0;
        for (var entry : positionsByIsin.entrySet()) {
            String isin = entry.getKey();
            JsonNode pos = entry.getValue();
            BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));
            BigDecimal price = tickerPrices.get(isin);

            if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                totalPortfolioValue = totalPortfolioValue.add(
                        price.multiply(size).setScale(2, RoundingMode.HALF_UP));
                priced++;
            } else {
                BigDecimal avgBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                if (avgBuyIn.compareTo(BigDecimal.ZERO) > 0) {
                    totalPortfolioValue = totalPortfolioValue.add(
                            avgBuyIn.multiply(size).setScale(2, RoundingMode.HALF_UP));
                    log.warn("TR ticker price missing for {}, using averageBuyIn as fallback", isin);
                }
            }
        }

        log.info("TR portfolio: {} positions, {} with live prices, total value: {}",
                 positionsByIsin.size(), priced, totalPortfolioValue);

        if (totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0) {
            accounts.add(new TrAccountData(
                    "tr_securities", "TR Titres", AccountType.COMPTE_TITRES, totalPortfolioValue));
        }

        if (cashJson.get() != null
                && accounts.stream().noneMatch(a -> "tr_cash".equals(a.externalId()))) {
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

    private int extractWsId(String text) {
        int space = text.indexOf(' ');
        if (space <= 0) return -1;
        try {
            return Integer.parseInt(text.substring(0, space));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String extractWsPayload(String text) {
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
            return "sub " + id + " " + objectMapper.writeValueAsString(
                    Map.of("type", type, "token", token));
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage());
        }
    }

    private String subWithId(int id, String type, String idParam, String token) {
        try {
            return "sub " + id + " " + objectMapper.writeValueAsString(
                    Map.of("type", type, "id", idParam, "token", token));
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage());
        }
    }

    private String subCompactPortfolio(int id, String secAccNo, String token) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "compactPortfolio");
            payload.put("secAccNo", secAccNo);
            payload.put("token", token);
            return "sub " + id + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage());
        }
    }

    private List<String> extractSecAccountNumbers(String sessionToken) {
        try {
            String[] parts = sessionToken.split("\\.");
            if (parts.length < 2) return List.of();
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            JsonNode root = objectMapper.readTree(payload);
            JsonNode secAccounts = root.path("act").path("acc").path("owner").path("default").path("sec");
            if (secAccounts.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode acc : secAccounts) {
                    result.add(acc.asText());
                }
                return result;
            }
        } catch (Exception ex) {
            log.warn("Failed to extract sec account numbers from JWT: {}", ex.getMessage());
        }
        return List.of();
    }

    private List<TrAccountData> parseCashJson(String json) {
        log.info("TR availableCash raw: {}", json);
        List<TrAccountData> accounts = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("availableCash");
            if (array.isMissingNode()) array = root;

            if (array.isArray()) {
                for (JsonNode item : array) {
                    BigDecimal value = extractValue(item);
                    if (value.compareTo(BigDecimal.ZERO) >= 0) {
                        accounts.add(new TrAccountData("tr_cash", "TR Cash", AccountType.CHECKING, value));
                        break;
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

    private BigDecimal extractValue(JsonNode node) {
        if (node == null || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.has("value"))   return new BigDecimal(node.get("value").asText("0"));
        if (node.has("amount"))  return new BigDecimal(node.get("amount").asText("0"));
        if (node.isNumber())     return node.decimalValue();
        return BigDecimal.ZERO;
    }
}
