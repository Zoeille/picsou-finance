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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolanaWalletAdapterTest {

    private static final String ADDRESS = "So11111111111111111111111111111111111111112";

    private static final String SOL_1 = """
        {"jsonrpc":"2.0","id":1,"result":{"value":1000000000}}""";

    private static final String SOL_ZERO = """
        {"jsonrpc":"2.0","id":1,"result":{"value":0}}""";

    private static final String TOKENS_EMPTY = """
        {"jsonrpc":"2.0","id":2,"result":{"value":[]}}""";

    // One USDC account (50 USDC) plus one unknown mint that must be dropped.
    private static final String TOKENS_USDC_AND_UNKNOWN = """
        {"jsonrpc":"2.0","id":2,"result":{"value":[
          {"account":{"data":{"parsed":{"info":{
            "mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "tokenAmount":{"uiAmountString":"50"}}}}}},
          {"account":{"data":{"parsed":{"info":{
            "mint":"UnknownMint1111111111111111111111111111111",
            "tokenAmount":{"uiAmountString":"999"}}}}}}
        ]}}""";

    private static final String RPC_ERROR = """
        {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"rate limited"}}""";

    /**
     * {@code fetchBalances} issues two sequential POSTs to the same URL
     * (getBalance, then getTokenAccountsByOwner), so responses are returned in
     * call order rather than routed by URL.
     */
    private SolanaWalletAdapter adapterReturning(String... jsonInOrder) {
        AtomicInteger index = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            String body = jsonInOrder[index.getAndIncrement()];
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
        };
        return new SolanaWalletAdapter(WebClient.builder().exchangeFunction(exchange).build());
    }

    @Test
    void fetchBalances_returnsSolAndKnownTokens_dropsUnknownMint() {
        var adapter = adapterReturning(SOL_1, TOKENS_USDC_AND_UNKNOWN);

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances.get(0).symbol()).isEqualTo("SOL");
        assertThat(balances.get(0).amount()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.symbol()).isEqualTo("USDC");
            assertThat(b.amount()).isEqualByComparingTo(new BigDecimal("50"));
        });
        assertThat(balances).noneSatisfy(b ->
            assertThat(b.amount()).isEqualByComparingTo(new BigDecimal("999")));
    }

    @Test
    void fetchBalances_returnsZeroSol_forLegitimateEmptyWallet() {
        var adapter = adapterReturning(SOL_ZERO, TOKENS_EMPTY);

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("SOL");
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void fetchBalances_throws_whenGetBalanceReturnsRpcError() {
        var adapter = adapterReturning(RPC_ERROR, TOKENS_EMPTY);

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("rate limited");
    }

    @Test
    void fetchBalances_throws_whenTokenCallReturnsRpcError() {
        // SOL succeeds but the SPL-token call errors -- fail the whole sync
        // rather than silently under-reporting stablecoin holdings.
        var adapter = adapterReturning(SOL_1, RPC_ERROR);

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("rate limited");
    }
}
