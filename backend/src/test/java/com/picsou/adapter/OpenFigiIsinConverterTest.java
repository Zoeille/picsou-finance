package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFigiIsinConverterTest {

    private static Map<String, Object> entry(String exchCode, String ticker, String name) {
        return Map.of("exchCode", exchCode, "ticker", ticker, "name", name);
    }

    @Test
    void isIsin_recognizesValidIsinCodes() {
        // 2-letter country prefix + 9 alphanumerics + 1 check digit = 12 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y983")).isTrue(); // iShares Core MSCI World
        assertThat(OpenFigiIsinConverter.isIsin("US0378331005")).isTrue(); // Apple
        assertThat(OpenFigiIsinConverter.isIsin("DE0007100000")).isTrue(); // Mercedes-Benz
        assertThat(OpenFigiIsinConverter.isIsin("KYG9830T1067")).isTrue(); // Xiaomi
    }

    @Test
    void isIsin_normalizesCaseAndWhitespace() {
        assertThat(OpenFigiIsinConverter.isIsin("ie00b4l5y983")).isTrue();
        assertThat(OpenFigiIsinConverter.isIsin("  IE00B4L5Y983  ")).isTrue();
    }

    @Test
    void isIsin_rejectsTickersAndNonIsinStrings() {
        assertThat(OpenFigiIsinConverter.isIsin("IWDA.AS")).isFalse(); // Yahoo ticker (has a dot)
        assertThat(OpenFigiIsinConverter.isIsin("AAPL")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y98")).isFalse();  // 11 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y9833")).isFalse(); // 13 chars
        assertThat(OpenFigiIsinConverter.isIsin("12345678901X")).isFalse();  // digits in country position
    }

    @Test
    void isIsin_rejectsNullAndBlank() {
        assertThat(OpenFigiIsinConverter.isIsin(null)).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("   ")).isFalse();
    }

    @Test
    void isTrCryptoIsin_detectsXf000PrefixCaseAndWhitespaceInsensitively() {
        // Shared with TradeRepublicAdapter's exchange choice; must tolerate the same
        // case/whitespace variants resolve()'s normalization does (unlike a raw startsWith).
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("XF000BTC0017")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(" xf000btc0017 ")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("IE00B4L5Y983")).isFalse(); // real ISIN
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(null)).isFalse();
    }

    @Test
    void resolve_parsesTickerAndNameForTradeRepublicCryptoIsins() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        // Ticker is now the parsed symbol (not the fake ISIN), so the holding becomes
        // price-resolvable via CoinGeckoPriceProvider instead of staying stuck on averageBuyIn.
        OpenFigiIsinConverter.TickerResult btc = converter.resolve("XF000BTC0017");
        assertThat(btc.ticker()).isEqualTo("BTC");
        assertThat(btc.name()).isEqualTo("Bitcoin");

        OpenFigiIsinConverter.TickerResult eth = converter.resolve("XF000ETH0017");
        assertThat(eth.ticker()).isEqualTo("ETH");
        assertThat(eth.name()).isEqualTo("Ethereum");
    }

    @Test
    void resolve_parsesAnyKnownCryptoSymbolNotJustBtcAndEth() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        // The symbol is parsed generically from the "XF000<SYMBOL><digits>" pattern and
        // validated against CoinGeckoPriceProvider's known tickers -- SOL isn't hardcoded
        // anywhere in OpenFigiIsinConverter (GH issue #22). The display name is derived
        // from the provider's coin registry too, so every known coin gets a real name,
        // including multi-word ids ("matic-network" -> "Matic Network").
        OpenFigiIsinConverter.TickerResult sol = converter.resolve("XF000SOL0042");
        assertThat(sol.ticker()).isEqualTo("SOL");
        assertThat(sol.name()).isEqualTo("Solana");

        OpenFigiIsinConverter.TickerResult matic = converter.resolve("XF000MATIC0099");
        assertThat(matic.ticker()).isEqualTo("MATIC");
        assertThat(matic.name()).isEqualTo("Matic Network");
    }

    @Test
    void resolve_normalizesCaseAndWhitespaceConsistently() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        OpenFigiIsinConverter.TickerResult padded = converter.resolve(" xf000btc0017 ");
        assertThat(padded.ticker()).isEqualTo("BTC");
        assertThat(padded.name()).isEqualTo("Bitcoin");
    }

    // ─── pickBest exchange priority ─────────────────────────────────────────────
    // A 2026-08-05 attempt to prefer EU exchanges over US OTC/ADR for Irish/
    // Luxembourg-domiciled ISINs (no HOME_EXCHANGE entry for either) was tried
    // and reverted: it fixed IE000BI8OT95 ("MWRD") but broke two other real
    // holdings on the same live portfolio (IE00BGSF1X88, IE00BD6FTQ80) whose EU
    // tickers have no live Yahoo quote while their US OTC ones do. See the
    // class-level Javadoc on pickBest() for the full account. These tests lock
    // in the reverted (original) US-OTC-first behavior.

    private final OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

    @Test
    void pickBest_prefersUsOtcOverEuExchangeWhenNoHomeExchangeMatches() {
        // IE: no HOME_EXCHANGE entry — mirrors IE000BI8OT95 exactly (FP + US both present).
        List<Map<String, Object>> entries = List.of(
            entry("US", "MWRDF", "AMUNDI MSCI WORLD USD ACC"),
            entry("FP", "MWRD", "AM CORE MSCI WORLD U ETF ACC")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("IE000BI8OT95", entries);

        assertThat(result.ticker()).isEqualTo("MWRDF");
        assertThat(result.name()).isEqualTo("AMUNDI MSCI WORLD USD ACC");
    }

    @Test
    void pickBest_fallsBackToEuExchangeWhenNoUsOtcAvailable() {
        List<Map<String, Object>> entries = List.of(
            entry("FP", "SOMEF", "SOME FUND WITH NO US OTC LISTING")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("IE00XXXXXXXX", entries);

        assertThat(result.ticker()).isEqualTo("SOMEF.PA");
    }

    @Test
    void pickBest_homeExchangeStillWinsOverUsAndEu() {
        // FR has a HOME_EXCHANGE entry (FP) — must still short-circuit before the
        // US/EU fallback ordering below it.
        List<Map<String, Object>> entries = List.of(
            entry("US", "BNPQY", "BNP PARIBAS ADR"),
            entry("FP", "BNP", "BNP PARIBAS SA"),
            entry("GR", "BNP", "BNP PARIBAS SA")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("FR0000131104", entries);

        assertThat(result.ticker()).isEqualTo("BNP.PA");
    }

    @Test
    void pickBest_fallsBackToAnyKnownExchangeWhenNoHomeUsOrEuMatch() {
        List<Map<String, Object>> entries = List.of(
            entry("HK", "1234", "SOME HONG KONG LISTING")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("KYG000000000", entries);

        assertThat(result.ticker()).isEqualTo("1234.HK");
    }

    @Test
    void pickBest_rejectsBondDescriptionsThatAreNotSymbols() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        List<Map<String, Object>> entries = List.of(Map.of(
            "ticker", "AIRBAL 14.5 08/14/29 REGS",
            "exchCode", "EURONEXT-DUBLIN",
            "name", "AIR BALTIC CORPORATION"));

        assertThat(converter.pickBest("XS2657412201", entries)).isNull();
    }

    @Test
    void pickBest_stillResolvesRealSymbolsOnKnownExchanges() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        List<Map<String, Object>> entries = List.of(Map.of(
            "ticker", "MBG",
            "exchCode", "GY",
            "name", "MERCEDES-BENZ GROUP AG"));

        OpenFigiIsinConverter.TickerResult equity = converter.pickBest("DE0007100000", entries);

        assertThat(equity).isNotNull();
        assertThat(equity.ticker()).isEqualTo("MBG.DE");
    }

    @Test
    void pickBest_returnsTheNormalizedSymbol_notTheRawOpenFigiValue() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(new CoinGeckoPriceProvider());

        List<Map<String, Object>> padded = List.of(Map.of(
            "ticker", " mbg ",
            "exchCode", "GY",
            "name", "MERCEDES-BENZ GROUP AG"));

        assertThat(converter.pickBest("DE0007100000", padded).ticker()).isEqualTo("MBG.DE");

        List<Map<String, Object>> unknownExchange = List.of(Map.of(
            "ticker", " aapl ",
            "exchCode", "NOT LISTED",
            "name", "APPLE INC"));

        assertThat(converter.pickBest("US0378331005", unknownExchange).ticker()).isEqualTo("AAPL");
    }
}
