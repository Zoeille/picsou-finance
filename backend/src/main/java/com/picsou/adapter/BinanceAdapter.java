package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.port.CryptoExchangePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class BinanceAdapter implements CryptoExchangePort {

    private static final Logger log = LoggerFactory.getLogger(BinanceAdapter.class);
    private static final String BASE_URL = "https://api.binance.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public BinanceAdapter() {
        this.webClient = WebClient.builder()
            .baseUrl(BASE_URL)
            .build();
    }

    @Override
    public String exchangeName() {
        return "BINANCE";
    }

    @Override
    public List<CryptoHolding> fetchHoldings(String apiKey, String apiSecret) {
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp;
        String signature = hmacSha256(apiSecret, queryString);

        JsonNode response = webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/v3/account")
                .query(queryString + "&signature=" + signature)
                .build())
            .header("X-MBX-APIKEY", apiKey)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(TIMEOUT)
            .block();

        if (response == null) return List.of();

        List<CryptoHolding> holdings = new ArrayList<>();
        JsonNode balances = response.path("balances");
        if (balances.isArray()) {
            for (JsonNode balance : balances) {
                String asset = balance.path("asset").asText("");
                BigDecimal free = new BigDecimal(balance.path("free").asText("0"));
                BigDecimal locked = new BigDecimal(balance.path("locked").asText("0"));
                BigDecimal total = free.add(locked);
                if (total.compareTo(BigDecimal.ZERO) > 0) {
                    holdings.add(new CryptoHolding(asset.toUpperCase(), total));
                }
            }
        }

        log.info("Binance: fetched {} non-zero holdings", holdings.size());
        return holdings;
    }

    @Override
    public boolean testConnection(String apiKey, String apiSecret) {
        try {
            long timestamp = System.currentTimeMillis();
            String queryString = "timestamp=" + timestamp;
            String signature = hmacSha256(apiSecret, queryString);

            webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v3/account")
                    .query(queryString + "&signature=" + signature)
                    .build())
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .block();
            return true;
        } catch (Exception ex) {
            log.warn("Binance connection test failed: {}", ex.getMessage());
            return false;
        }
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception ex) {
            throw new RuntimeException("HMAC-SHA256 signing failed", ex);
        }
    }
}
