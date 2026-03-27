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

@Component
public class BitcoinWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(BitcoinWalletAdapter.class);
    private static final String BASE_URL = "https://blockstream.info";
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");

    private final WebClient webClient;

    public BitcoinWalletAdapter() {
        this.webClient = WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override
    public String chain() {
        return "BITCOIN";
    }

    @Override
    public WalletBalance fetchBalance(String address) {
        JsonNode response = webClient.get()
            .uri("/api/address/{address}", address)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        if (response == null) {
            log.warn("Blockstream returned null for address {}", address);
            return new WalletBalance("BTC", BigDecimal.ZERO);
        }

        long fundedSats = response.path("chain_stats").path("funded_txo_sum").asLong(0);
        long spentSats = response.path("chain_stats").path("spent_txo_sum").asLong(0);
        long balanceSats = fundedSats - spentSats;
        BigDecimal btc = new BigDecimal(balanceSats).divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP);

        log.info("Bitcoin balance for {}: {} BTC", address, btc);
        return new WalletBalance("BTC", btc);
    }
}
