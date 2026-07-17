package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.adapter.util.JsonRpcResponse;
import com.picsou.exception.WalletRpcException;
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
    private static final String RPC_URL = "https://ethereum-rpc.publicnode.com";
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");

    private final WebClient webClient;

    public EthereumWalletAdapter() {
        this(WebClient.builder()
            .baseUrl(RPC_URL)
            .defaultHeader("Content-Type", "application/json")
            .build());
    }

    // Package-private seam for tests: inject a WebClient backed by an ExchangeFunction.
    EthereumWalletAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String chain() {
        return "ETHEREUM";
    }

    @Override
    public List<WalletBalance> fetchBalances(String address) {
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

        JsonNode result = JsonRpcResponse.requireResult(response, "Ethereum eth_getBalance");
        String hexBalance = result.asText();
        BigInteger wei = parseHexWei(hexBalance);
        BigDecimal eth = new BigDecimal(wei).divide(WEI_PER_ETH, 18, RoundingMode.HALF_UP);

        log.info("Ethereum balance for {}: {} ETH", address, eth);
        return List.of(new WalletBalance("ETH", eth));
    }

    /**
     * Parses a {@code 0x}-prefixed hex wei string. A malformed value (missing
     * prefix, non-hex digits) is treated as a broken RPC response — throwing
     * rather than letting a raw {@link NumberFormatException} surface as an
     * opaque 500.
     */
    private static BigInteger parseHexWei(String hexBalance) {
        if (hexBalance == null || !hexBalance.startsWith("0x")) {
            throw new WalletRpcException(
                "Ethereum eth_getBalance: malformed hex balance '" + hexBalance + "'");
        }
        try {
            return new BigInteger(hexBalance.substring(2), 16);
        } catch (NumberFormatException ex) {
            throw new WalletRpcException(
                "Ethereum eth_getBalance: malformed hex balance '" + hexBalance + "'");
        }
    }
}
