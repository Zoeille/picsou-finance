package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class EthereumWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(EthereumWalletAdapter.class);
    private static final String RPC_URL = "https://cloudflare-eth.com";
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");

    private final WebClient webClient;

    public EthereumWalletAdapter() {
        this.webClient = WebClient.builder()
            .baseUrl(RPC_URL)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    @Override
    public String chain() {
        return "ETHEREUM";
    }

    @Override
    public WalletBalance fetchBalance(String address) {
        Map<String, Object> rpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "eth_getBalance",
            "params", List.of(address, "latest")
        );

        JsonNode response = webClient.post()
            .bodyValue(rpcRequest)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        if (response == null) {
            log.warn("Ethereum RPC returned null for address {}", address);
            return new WalletBalance("ETH", BigDecimal.ZERO);
        }

        String hexBalance = response.path("result").asText("0x0");
        BigInteger wei = new BigInteger(hexBalance.substring(2), 16);
        BigDecimal eth = new BigDecimal(wei).divide(WEI_PER_ETH, 18, RoundingMode.HALF_UP);

        log.info("Ethereum balance for {}: {} ETH", address, eth);
        return new WalletBalance("ETH", eth);
    }
}
