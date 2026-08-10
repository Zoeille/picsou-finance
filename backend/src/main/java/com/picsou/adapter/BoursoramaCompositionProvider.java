package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.WeightedSlice;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches ETF composition from Boursorama's public tracker pages — no auth.
 *
 * Two-step flow:
 *  1. Resolve {@code ticker → Boursorama symbol} via the search endpoint, which
 *     302-redirects to {@code /cours/{SYMBOL}/}; the symbol is read from the
 *     Location header. The exchange suffix is stripped first (PUST.PA → PUST).
 *  2. Fetch {@code /bourse/trackers/cours/composition/{SYMBOL}/} and parse two
 *     inline amCharts JSON blocks (regional, sector) plus the {@code c-table-gauge}
 *     holdings table.
 *
 * French sector/country labels are normalised to stable keys via
 * {@link BoursoramaLabels}; the frontend translates them. The page layout is
 * unofficial and may change; failures are swallowed and surface as
 * "composition unavailable" upstream.
 */
@Component
public class BoursoramaCompositionProvider implements EtfCompositionProvider {

    private static final Logger log = LoggerFactory.getLogger(BoursoramaCompositionProvider.class);
    private static final String SOURCE = "Boursorama";
    private static final String HOST = "https://www.boursorama.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int TOP_COMPANIES = 10;

    // Composition lives under /trackers for ETFs; /opcvm is a fallback for funds.
    private static final String[] COMPOSITION_PATHS = {
        "/bourse/trackers/cours/composition/{s}/",
        "/bourse/opcvm/cours/composition/{s}/"
    };

    private static final Pattern SYMBOL = Pattern.compile("/cours/([^/?]+)/");
    // NOTE: the [^\]]* capture truncates the array if a label contains a literal ']';
    // readTree() then throws and toSlices() returns empty (fail-soft, acceptable).
    private static final Pattern AMCHART = Pattern.compile(
        "\"id\":\"(regional|sector)\".*?\"amChartData\":(\\[[^\\]]*\\])", Pattern.DOTALL);
    private static final Pattern HOLDING_ROW = Pattern.compile(
        "c-table-gauge__cell--header\">\\s*(.*?)\\s*</td>.*?data-gauge-current-step=\"([0-9.,]+)\"",
        Pattern.DOTALL);
    private static final Pattern AS_OF = Pattern.compile(
        "Date du portefeuille\\s*:\\s*(\\d{2})/(\\d{2})/(\\d{4})");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;

    public BoursoramaCompositionProvider() {
        this(WebClient.builder()
            .baseUrl(HOST)
            // Read the 302 Location ourselves instead of following the redirect.
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept-Language", "fr-FR")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    BoursoramaCompositionProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String ticker, String name) {
        return ticker != null && !ticker.isBlank();
    }

    @Override
    public Optional<EtfComposition> fetch(String ticker, String name) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        try {
            Optional<String> symbol = resolveSymbol(bareTicker(ticker));
            if (symbol.isEmpty()) {
                log.debug("Boursorama: no symbol resolved for {}", ticker);
                return Optional.empty();
            }
            Optional<String> html = fetchCompositionHtml(symbol.get());
            if (html.isEmpty()) {
                log.debug("Boursorama: no composition page for {} ({})", ticker, symbol.get());
                return Optional.empty();
            }
            return Optional.of(parse(html.get()));
        } catch (Exception ex) {
            log.warn("Boursorama composition fetch failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    // --- network ----------------------------------------------------------

    private Optional<String> resolveSymbol(String query) {
        String location = webClient.get()
            .uri(b -> b.path("/recherche/").queryParam("query", query).build())
            .exchangeToMono(resp -> Mono.justOrEmpty(resp.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION)))
            .timeout(TIMEOUT)
            .onErrorResume(e -> Mono.empty())
            .block();
        return symbolFromLocation(location);
    }

    private Optional<String> fetchCompositionHtml(String symbol) {
        for (String template : COMPOSITION_PATHS) {
            try {
                String html = webClient.get().uri(template, symbol)
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
                if (html != null && !html.isBlank() && html.contains("amChartData")) {
                    return Optional.of(html);
                }
            } catch (Exception ex) {
                log.debug("Boursorama composition path {} failed for {}: {}", template, symbol, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    // --- parsing (the testable core) --------------------------------------

    static Optional<String> symbolFromLocation(String location) {
        if (location == null) return Optional.empty();
        Matcher m = SYMBOL.matcher(location);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Strip the exchange suffix from a ticker ("PUST.PA" → "PUST"). */
    static String bareTicker(String ticker) {
        int dot = ticker.indexOf('.');
        return dot > 0 ? ticker.substring(0, dot) : ticker;
    }

    static EtfComposition parse(String html) {
        List<WeightedSlice> countries = List.of();
        List<WeightedSlice> sectors = List.of();
        Matcher m = AMCHART.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (id.equals("regional")) {
                countries = toSlices(m.group(2), BoursoramaLabels::countryKey);
            } else {
                sectors = toSlices(m.group(2), BoursoramaLabels::sectorKey);
            }
        }
        List<WeightedSlice> companies = parseHoldings(html);
        return new EtfComposition(companies, countries, sectors, SOURCE, parseAsOf(html));
    }

    private static List<WeightedSlice> toSlices(String jsonArray, Function<String, String> keyFn) {
        List<WeightedSlice> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(jsonArray);
            for (JsonNode node : arr) {
                String name = node.path("name").asText(null);
                JsonNode value = node.get("value");
                if (name == null || name.isBlank() || value == null || !value.isNumber()) continue;
                out.add(new WeightedSlice(keyFn.apply(name), scale(value.decimalValue())));
            }
        } catch (Exception ex) {
            log.debug("Boursorama amChart parse failed: {}", ex.getMessage());
        }
        return out;
    }

    private static List<WeightedSlice> parseHoldings(String html) {
        List<WeightedSlice> out = new ArrayList<>();
        Matcher m = HOLDING_ROW.matcher(html);
        while (m.find() && out.size() < TOP_COMPANIES) {
            String name = HtmlUtils.htmlUnescape(m.group(1)).trim();
            if (name.isEmpty() || isSwapLine(name)) continue;
            BigDecimal weight = parseWeight(m.group(2));
            if (weight == null) continue;
            out.add(new WeightedSlice(name, weight));
        }
        return out;
    }

    /** Synthetic ETFs list only their swap as a holding — not a real constituent. */
    private static boolean isSwapLine(String name) {
        String u = name.toUpperCase();
        return u.startsWith("TRS ") || u.contains("SWAP");
    }

    private static BigDecimal parseWeight(String raw) {
        try {
            return scale(new BigDecimal(raw.replace(",", ".")));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseAsOf(String html) {
        Matcher m = AS_OF.matcher(html);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException | NumberFormatException ex) {
            log.debug("Boursorama composition as-of date '{}' is not a valid date", m.group(0));
            return null;
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
