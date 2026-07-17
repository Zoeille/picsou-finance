package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.EvmWalletAdapter.Erc20Token;
import com.picsou.adapter.EvmWalletAdapter.EvmNetwork;
import com.picsou.adapter.EvmWalletAdapter.EvmRpc;
import com.picsou.exception.WalletRpcException;
import com.picsou.port.WalletPort.WalletBalance;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvmWalletAdapterTest {

    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Canned JSON-RPC envelopes ──────────────────────────────────────────
    // 10^18 wei = 1 coin; 2 * 10^18 = 2 coins.
    private static final String ONE_COIN = result("0xDE0B6B3A7640000");
    private static final String TWO_COINS = result("0x1BC16D674EC80000");
    private static final String ZERO = result("0x0");
    // 500 * 10^6 (6-decimal token) = 500000000 = 0x1DCD6500.
    private static final String FIVE_HUNDRED_6DEC = result("0x1DCD6500");
    private static final String EMPTY_CALL = result("0x");           // call to a non-contract
    private static final String MALFORMED_HEX = result("0xZZZ");     // non-hex digits
    private static final String RPC_ERROR = """
        {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}""";
    private static final String MISSING_RESULT = """
        {"jsonrpc":"2.0","id":1}""";

    // Sentinels the stub interprets specially (vs a JSON envelope string).
    private static final String TRANSPORT_ERROR = "__TRANSPORT_ERROR__"; // connection reset / 5xx / timeout
    private static final String NO_RESPONSE = "__NO_RESPONSE__";         // empty body

    private static String result(String hex) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    // ── Content-routed RPC stub (order-independent: networks run concurrently) ──
    private EvmWalletAdapter adapter(List<EvmNetwork> networks, Map<String, String> responses) {
        EvmRpc rpc = (url, request) -> {
            String key = routeKey(url, request);
            // Unspecified calls default to a benign zero result so tests only
            // declare the responses they care about.
            String canned = responses.getOrDefault(key,
                "eth_call".equals(request.get("method")) ? EMPTY_CALL : ZERO);
            if (TRANSPORT_ERROR.equals(canned)) {
                return Mono.error(new RuntimeException("simulated transport failure"));
            }
            if (NO_RESPONSE.equals(canned)) {
                return Mono.empty();
            }
            return Mono.just(parse(canned));
        };
        return new EvmWalletAdapter(rpc, networks);
    }

    private EvmWalletAdapter adapter(List<EvmNetwork> networks) {
        return adapter(networks, Map.of());
    }

    private static String routeKey(String url, Map<String, Object> request) {
        if ("eth_call".equals(request.get("method"))) {
            List<?> params = (List<?>) request.get("params");
            Map<?, ?> callObj = (Map<?, ?>) params.get(0);
            return url + "|" + callObj.get("to");
        }
        return url + "|native";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static EvmNetwork net(String name, String symbol, List<Erc20Token> tokens) {
        return new EvmNetwork(name, symbol, "https://" + name + ".example", tokens);
    }

    private static String nativeKey(String netName) {
        return "https://" + netName + ".example|native";
    }

    private static String tokenKey(String netName, String contract) {
        return "https://" + netName + ".example|" + contract;
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void fansOutAcrossNetworks_andAggregatesSameSymbol() {
        // Ethereum (1 ETH) + Arbitrum (2 ETH), both native ETH -> one aggregated 3 ETH entry.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of()), net("arb", "ETH", List.of())),
            Map.of(nativeKey("eth"), ONE_COIN, nativeKey("arb"), TWO_COINS));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("ETH");
            assertThat(b.amount()).isEqualByComparingTo("3");
        });
    }

    @Test
    void keepsDistinctNativeSymbols_andParsesTokenAtItsDecimals() {
        // BNB Chain: 1 BNB native + 500 USDC (6-decimal test token).
        var adapter = adapter(
            List.of(net("bsc", "BNB", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(nativeKey("bsc"), ONE_COIN, tokenKey("bsc", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances.get(0).symbol()).isEqualTo("BNB");   // native leads
        assertThat(balances.get(0).amount()).isEqualByComparingTo("1");
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.symbol()).isEqualTo("USDC");
            assertThat(b.amount()).isEqualByComparingTo("500");
        });
    }

    @Test
    void aggregatesTokenHeldOnMultipleChains() {
        // Same stablecoin on two chains sums into one holding. Natives default to
        // zero, so only the seeded ETH:0 leader and the aggregated USDC remain.
        var adapter = adapter(
            List.of(
                net("a", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6))),
                net("b", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(tokenKey("a", "0xUSDC"), FIVE_HUNDRED_6DEC, tokenKey("b", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances.get(0).symbol()).isEqualTo("ETH");   // seeded native leads
        assertThat(balances.get(0).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.symbol()).isEqualTo("USDC");
            assertThat(b.amount()).isEqualByComparingTo("1000");
        });
    }

    @Test
    void jsonRpcErrorOnNativeProbe_failsWholeSync() {
        // A chain's native RPC returning an error payload must fail the whole sync
        // (→ 422, wallet keeps its last balance) rather than silently dropping that
        // chain from net worth while still marking the wallet synced.
        var adapter = adapter(
            List.of(net("down", "ETH", List.of()), net("up", "BNB", List.of())),
            Map.of(nativeKey("down"), RPC_ERROR, nativeKey("up"), ONE_COIN));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void transportErrorOnNativeProbe_isWrappedAndFatal() {
        // A transport-level failure (connection reset / 5xx / timeout) is wrapped as
        // WalletRpcException so it's classified as an expected sync failure (WARN +
        // 422), not an unexpected bug -- and it still aborts the sync.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), TRANSPORT_ERROR));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void nullResponseOnNativeProbe_failsWholeSync() {
        // An empty body (dropped connection) must surface as a failure, not be read
        // as a 0 balance -- guards the reactive path's switchIfEmpty.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), NO_RESPONSE));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("no response");
    }

    @Test
    void malformedHexResult_failsSync() {
        // Non-hex digits in a balance result are a broken RPC payload -> throw
        // rather than let a NumberFormatException surface as an opaque 500.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), MALFORMED_HEX));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("malformed hex");
    }

    @Test
    void missingResultField_failsSync() {
        // A well-formed envelope with neither 'result' nor 'error' must not read as 0.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), MISSING_RESULT));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("result");
    }

    @Test
    void jsonRpcErrorOnOneToken_doesNotDropNativeOrOtherTokens() {
        // Native ok, first token errors (JSON-RPC payload), second token ok ->
        // native + good token survive.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(
                new Erc20Token("0xBAD", "DAI", 18),
                new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                tokenKey("eth", "0xBAD"), RPC_ERROR,
                tokenKey("eth", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("ETH"));
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("USDC"));
        assertThat(balances).noneSatisfy(b -> assertThat(b.symbol()).isEqualTo("DAI"));
    }

    @Test
    void transportErrorOnOneToken_isSkipped_notFatal() {
        // A transport error on a single balanceOf call must skip just that token,
        // not abort the whole multi-chain sync (regression guard: the catch must
        // cover wrapped transport errors, not only JSON-RPC error payloads).
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(
                new Erc20Token("0xBAD", "DAI", 18),
                new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                tokenKey("eth", "0xBAD"), TRANSPORT_ERROR,
                tokenKey("eth", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("ETH"));
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("USDC"));
        assertThat(balances).noneSatisfy(b -> assertThat(b.symbol()).isEqualTo("DAI"));
    }

    @Test
    void emptyCallResult_treatedAsZeroToken() {
        // A "0x" result (call to a non-contract) is not an error -- just no balance.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(nativeKey("eth"), ONE_COIN, tokenKey("eth", "0xUSDC"), EMPTY_CALL));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> assertThat(b.symbol()).isEqualTo("ETH"));
    }

    @Test
    void returnsZeroNative_forValidButEmptyWallet() {
        // Every balance is zero, but the network responded -> report ETH:0, not empty
        // (WalletSyncService would otherwise treat empty as an adapter failure).
        var adapter = adapter(List.of(net("eth", "ETH", List.of())), Map.of(nativeKey("eth"), ZERO));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("ETH");
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void malformedAddress_throwsBeforeAnyRpcCall() {
        // Over-long / non-hex addresses are rejected up front (guards padAddress,
        // whose '0'.repeat(64 - length) would otherwise throw for a long string).
        var adapter = adapter(List.of(net("eth", "ETH", List.of())));

        assertThatThrownBy(() -> adapter.fetchBalances("0x" + "1".repeat(41)))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("Malformed EVM address");
        assertThatThrownBy(() -> adapter.fetchBalances("not-an-address"))
            .isInstanceOf(WalletRpcException.class);
        assertThatThrownBy(() -> adapter.fetchBalances(null))
            .isInstanceOf(WalletRpcException.class);
    }
}
