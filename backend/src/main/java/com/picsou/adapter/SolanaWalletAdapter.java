package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;

@Component
public class SolanaWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(SolanaWalletAdapter.class);
    private static final String RPC_URL = "https://api.mainnet-beta.solana.com";
    private static final BigDecimal LAMPORTS_PER_SOL = new BigDecimal("1000000000");

    private final WebClient webClient;

    public SolanaWalletAdapter() {
        this.webClient = WebClient.builder()
            .baseUrl(RPC_URL)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    @Override
    public String chain() {
        return "SOLANA";
    }

    @Override
    public WalletBalance fetchBalance(String address) {
        Map<String, Object> rpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "getBalance",
            "params", new Object[]{address}
        );

        JsonNode response = webClient.post()
            .bodyValue(rpcRequest)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        if (response == null) {
            log.warn("Solana RPC returned null for address {}", address);
            return new WalletBalance("SOL", BigDecimal.ZERO);
        }

        long lamports = response.path("result").path("value").asLong(0);
        BigDecimal sol = new BigDecimal(lamports).divide(LAMPORTS_PER_SOL, 9, RoundingMode.HALF_UP);

        log.info("Solana balance for {}: {} SOL", address, sol);
        return new WalletBalance("SOL", sol);
    }
}
