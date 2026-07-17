package com.picsou.adapter;

import com.picsou.exception.WalletRpcException;
import com.picsou.port.WalletPort.WalletBalance;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EthereumWalletAdapterTest {

    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";

    private EthereumWalletAdapter adapterReturning(String json) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json)
            .build());
        return new EthereumWalletAdapter(WebClient.builder().exchangeFunction(exchange).build());
    }

    private EthereumWalletAdapter adapterReturningNoBody() {
        // An empty body maps to Mono.empty(), so .block() yields a null response --
        // the timeout / dropped-connection case.
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build());
        return new EthereumWalletAdapter(WebClient.builder().exchangeFunction(exchange).build());
    }

    @Test
    void fetchBalances_parsesHexWeiIntoEth() {
        // 0xDE0B6B3A7640000 = 10^18 wei = 1 ETH
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1,"result":"0xDE0B6B3A7640000"}""");

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("ETH");
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ONE);
        });
    }

    @Test
    void fetchBalances_returnsZero_forLegitimateEmptyWallet() {
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1,"result":"0x0"}""");

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b ->
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void fetchBalances_throws_onRpcError() {
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"rate limited"}}""");

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("rate limited");
    }

    @Test
    void fetchBalances_throws_onMissingResult() {
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1}""");

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("missing 'result'");
    }

    @Test
    void fetchBalances_throws_onNullResponseBody() {
        var adapter = adapterReturningNoBody();

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("no response");
    }

    @Test
    void fetchBalances_throws_onMalformedHexDigits() {
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1,"result":"0xINVALID"}""");

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("malformed hex");
    }

    @Test
    void fetchBalances_throws_onMissingHexPrefix() {
        var adapter = adapterReturning("""
            {"jsonrpc":"2.0","id":1,"result":"12345"}""");

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("malformed hex");
    }
}
