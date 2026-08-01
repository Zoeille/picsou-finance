package com.picsou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.PropertyValuationResponse;
import com.picsou.dto.PropertyValuationResponse.AdjustmentDto;
import com.picsou.model.*;
import com.picsou.port.GeocodingPort;
import com.picsou.port.HousingPriceIndexPort;
import com.picsou.port.PropertyValuationPort;
import com.picsou.port.PropertyValuationPort.ValuationInput;
import com.picsou.port.PropertyValuationPort.ValuationResult;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estimates what a property is worth and, unless the user has locked it, writes that onto
 * the account balance.
 *
 * <p><b>Why this is persisted rather than computed on read.</b> Loan amortisation is
 * recomputed on every request (see the loan-amortisation ADR) because its formula is local
 * and costs microseconds. A valuation depends on two external HTTP services, so recomputing
 * it per request would put a third party in the path of every dashboard load. Instead the
 * monthly job writes {@code account.currentBalance}, and {@code AccountService.liveBalanceEur}
 * is left completely untouched — a valued property behaves exactly like any manual account.
 * The daily snapshot job then captures the gain curve for free.
 */
@Service
public class PropertyValuationService {

    private static final Logger log = LoggerFactory.getLogger(PropertyValuationService.class);

    /** Below this geocoding score the match is too shaky to value against. */
    private static final BigDecimal MIN_GEOCODE_SCORE = new BigDecimal("0.4");

    private final List<PropertyValuationPort> providers;
    private final GeocodingPort geocoder;
    private final HousingPriceIndexPort priceIndex;
    private final PropertyAdjustments adjustments;
    private final RealEstateMetadataRepository metadataRepository;
    private final PropertyValuationRepository valuationRepository;
    private final AccountRepository accountRepository;
    private final AccountAccessResolver accessResolver;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public PropertyValuationService(
        List<PropertyValuationPort> providers,
        GeocodingPort geocoder,
        HousingPriceIndexPort priceIndex,
        PropertyAdjustments adjustments,
        RealEstateMetadataRepository metadataRepository,
        PropertyValuationRepository valuationRepository,
        AccountRepository accountRepository,
        AccountAccessResolver accessResolver,
        ObjectMapper objectMapper,
        @Value("${app.valuation.enabled:true}") boolean enabled
    ) {
        this.providers = providers;
        this.geocoder = geocoder;
        this.priceIndex = priceIndex;
        this.adjustments = adjustments;
        this.metadataRepository = metadataRepository;
        this.valuationRepository = valuationRepository;
        this.accountRepository = accountRepository;
        this.accessResolver = accessResolver;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /** Stored estimates, newest first. Readable by co-owners. */
    @Transactional(readOnly = true)
    public List<PropertyValuation> history(Long accountId, Long memberId) {
        accessResolver.requireReadable(accountId, memberId);
        return valuationRepository.findByAccountIdOrderByValuedAtDesc(accountId);
    }

    // ─── Estimate ────────────────────────────────────────────────────────────

    /**
     * Values one property.
     *
     * <p>Owner-only: an estimate can move the account balance, and a co-owner must not be
     * able to rewrite the owner's net worth.
     */
    @Transactional
    public PropertyValuationResponse estimate(Long accountId, Long memberId) {
        Account account = accessResolver.requireOwner(accountId, memberId);
        if (account.getType() != AccountType.REAL_ESTATE) {
            throw new IllegalArgumentException("Account is not a real estate account");
        }
        RealEstateMetadata metadata = metadataRepository.findByAccountId(accountId)
            .orElseThrow(() -> new IllegalStateException("Property has no metadata yet"));

        return estimateFor(account, metadata);
    }

    /**
     * Values every property of a member, for the scheduled refresh.
     *
     * <p>Guarded per property: one unreachable commune must not abort the rest.
     */
    @Transactional
    public int refreshAllForMember(Long memberId) {
        if (!enabled) {
            return 0;
        }
        int refreshed = 0;
        for (Account account : accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
            if (account.getType() != AccountType.REAL_ESTATE) {
                continue;
            }
            // The metadata lookup belongs inside the guard: it hits the database, so it can
            // fail too, and a single bad property must not abort the whole member's refresh.
            try {
                Optional<RealEstateMetadata> metadata = metadataRepository.findByAccountId(account.getId());
                if (metadata.isEmpty()) {
                    continue;
                }
                PropertyValuationResponse result = estimateFor(account, metadata.get());
                if (result.status() == ValuationStatus.OK) {
                    refreshed++;
                }
            } catch (Exception ex) {
                log.error("Valuation failed for property {} (member {}) — skipping it",
                    account.getId(), memberId, ex);
            }
        }
        return refreshed;
    }

    private PropertyValuationResponse estimateFor(Account account, RealEstateMetadata metadata) {
        ValuationMode mode = metadata.getValuationMode();

        if (!enabled) {
            return PropertyValuationResponse.failed(ValuationStatus.PROVIDER_UNAVAILABLE, mode);
        }
        PropertyKind kind = metadata.kind();
        if (kind == null || !kind.isEstimable()) {
            return PropertyValuationResponse.failed(ValuationStatus.NOT_ESTIMABLE, mode);
        }
        if (metadata.getSurfaceArea() == null || metadata.getSurfaceArea().signum() <= 0) {
            return PropertyValuationResponse.failed(ValuationStatus.INCOMPLETE_DATA, mode);
        }

        ValuationStatus geocodeStatus = geocodeIfNeeded(metadata);
        if (geocodeStatus != ValuationStatus.OK) {
            return PropertyValuationResponse.failed(geocodeStatus, mode);
        }

        ValuationInput input = toInput(metadata, kind);

        PropertyValuationPort provider = providers.stream()
            .filter(p -> p.supports(input))
            .findFirst()
            .orElse(null);
        if (provider == null) {
            // No provider covers this area at all — Alsace-Moselle and Mayotte are the
            // structural cases, and the user needs to be told that rather than shown a guess.
            return PropertyValuationResponse.failed(ValuationStatus.UNSUPPORTED_AREA, mode);
        }

        Optional<ValuationResult> raw = provider.estimate(input);
        if (raw.isEmpty()) {
            return PropertyValuationResponse.failed(ValuationStatus.NO_COMPARABLE_DATA, mode);
        }
        ValuationResult result = raw.get();

        // 1. Correct the commune median for this specific property.
        PropertyAdjustments.Result adjusted =
            adjustments.compute(metadata, result.estimatedValue(), result.pricePerSqm());

        // 2. Carry it forward from the data's vintage to today.
        BigDecimal reindexRatio = reindexRatio(result.sourceYear(), kind);
        BigDecimal finalValue = adjusted.value();
        BigDecimal low = result.lowValue();
        BigDecimal high = result.highValue();
        if (reindexRatio != null) {
            finalValue = finalValue.multiply(reindexRatio);
            low = low != null ? low.multiply(reindexRatio) : null;
            high = high != null ? high.multiply(reindexRatio) : null;
        }
        finalValue = finalValue.setScale(2, RoundingMode.HALF_UP);

        PropertyValuation saved = persist(account, metadata, provider.providerName(), result,
            finalValue, low, high, adjusted, reindexRatio);

        boolean applied = mode == ValuationMode.ESTIMATED;
        if (applied) {
            account.setCurrentBalance(finalValue);
            accountRepository.save(account);
        }

        return PropertyValuationResponse.from(
            saved, mode, applied, reindexRatio, toDtos(adjusted.applied()), result.scale());
    }

    private ValuationInput toInput(RealEstateMetadata m, PropertyKind kind) {
        String insee = m.getInseeCode();
        String department = null;
        if (insee != null && insee.length() >= 2) {
            department = insee.startsWith("97") && insee.length() >= 3
                ? insee.substring(0, 3)
                : insee.substring(0, 2);
        }
        return new ValuationInput(
            insee,
            department,
            m.getCountry(),
            kind,
            m.getSurfaceArea(),
            m.getLandArea(),
            m.getRooms(),
            m.getConstructionYear(),
            m.getLatitude(),
            m.getLongitude()
        );
    }

    /**
     * Resolves the address once and caches the result on the property.
     *
     * <p>Re-geocoding happens only when {@code inseeCode} is missing — the metadata update
     * path clears it whenever the address changes, which is what makes this cheap enough to
     * call on every estimate.
     */
    private ValuationStatus geocodeIfNeeded(RealEstateMetadata m) {
        if (m.getInseeCode() != null && !m.getInseeCode().isBlank()) {
            return ValuationStatus.OK;
        }
        String query = buildAddressQuery(m);
        if (query.isBlank()) {
            return ValuationStatus.INCOMPLETE_DATA;
        }
        Optional<GeocodingPort.GeocodeResult> hit = geocoder.geocode(query);
        if (hit.isEmpty()) {
            return ValuationStatus.GEOCODING_FAILED;
        }
        GeocodingPort.GeocodeResult g = hit.get();
        if (g.inseeCode() == null || g.inseeCode().isBlank()) {
            return ValuationStatus.GEOCODING_FAILED;
        }
        if (g.score() != null && g.score().compareTo(MIN_GEOCODE_SCORE) < 0) {
            // A weak match usually means a typo or an incomplete address. Valuing the wrong
            // commune is worse than asking the user to fix it.
            log.info("Geocoding score {} too low for '{}' — refusing to value on it", g.score(), query);
            return ValuationStatus.GEOCODING_FAILED;
        }

        m.setInseeCode(g.inseeCode());
        m.setLatitude(g.latitude());
        m.setLongitude(g.longitude());
        m.setBanId(g.banId());
        m.setGeocodeScore(g.score());
        m.setGeocodedAt(java.time.Instant.now());
        if (m.getPostalCode() == null || m.getPostalCode().isBlank()) {
            m.setPostalCode(g.postcode());
        }
        if (m.getCity() == null || m.getCity().isBlank()) {
            m.setCity(g.city());
        }
        metadataRepository.save(m);
        return ValuationStatus.OK;
    }

    private String buildAddressQuery(RealEstateMetadata m) {
        StringBuilder sb = new StringBuilder();
        if (m.getAddress() != null) sb.append(m.getAddress().trim());
        if (m.getPostalCode() != null && !m.getPostalCode().isBlank()) sb.append(' ').append(m.getPostalCode().trim());
        if (m.getCity() != null && !m.getCity().isBlank()) sb.append(' ').append(m.getCity().trim());
        return sb.toString().trim();
    }

    /**
     * How much prices moved between the source vintage and now.
     *
     * <p>The vintage is a calendar year, so it is treated as its midpoint — using January
     * would systematically over-correct by half a year.
     */
    private BigDecimal reindexRatio(Short sourceYear, PropertyKind kind) {
        if (sourceYear == null) {
            return null;
        }
        YearMonth from = YearMonth.of(sourceYear, 7);
        YearMonth to = YearMonth.now();
        if (!from.isBefore(to)) {
            return null;
        }
        return priceIndex.reindexRatio(from, to, kind).orElse(null);
    }

    private PropertyValuation persist(Account account, RealEstateMetadata metadata,
                                      String providerName, ValuationResult result,
                                      BigDecimal finalValue, BigDecimal low, BigDecimal high,
                                      PropertyAdjustments.Result adjusted,
                                      BigDecimal reindexRatio) {
        LocalDate today = LocalDate.now();
        // Upsert on (account, day): a user hitting refresh repeatedly should correct today's
        // estimate, not litter the history with near-identical rows.
        PropertyValuation valuation = valuationRepository
            .findByAccountIdAndValuedAt(account.getId(), today)
            .orElseGet(() -> PropertyValuation.builder()
                .account(account)
                .member(metadata.getMember())
                .valuedAt(today)
                .build());

        valuation.setEstimatedValue(finalValue);
        valuation.setLowValue(scaleOrNull(low));
        valuation.setHighValue(scaleOrNull(high));
        valuation.setPricePerSqm(result.pricePerSqm());
        valuation.setProvider(providerName);
        valuation.setConfidence(result.confidence());
        valuation.setSampleSize(result.sampleSize());
        valuation.setSourceYear(result.sourceYear());
        valuation.setMethodDetail(methodDetailJson(result, adjusted, reindexRatio));
        return valuationRepository.save(valuation);
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Freezes how this estimate was built.
     *
     * <p>Written as JSON rather than columns because it is explanatory, not queryable, and
     * the coefficient set will change over time — an old row must still describe the rules
     * that actually produced it.
     */
    private String methodDetailJson(ValuationResult result, PropertyAdjustments.Result adjusted,
                                    BigDecimal reindexRatio) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("basePricePerSqm", result.pricePerSqm());
        detail.put("baseValue", result.estimatedValue());
        detail.put("scale", result.scale());
        detail.put("sourceYear", result.sourceYear());
        detail.put("sampleSize", result.sampleSize());
        detail.put("multiplier", adjusted.multiplier());
        detail.put("reindexRatio", reindexRatio);
        detail.put("adjustments", toDtos(adjusted.applied()));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            log.warn("Could not serialise valuation method detail", ex);
            return null;
        }
    }

    private static List<AdjustmentDto> toDtos(List<PropertyAdjustments.Adjustment> applied) {
        List<AdjustmentDto> dtos = new ArrayList<>(applied.size());
        for (PropertyAdjustments.Adjustment a : applied) {
            dtos.add(new AdjustmentDto(a.code(), a.factor(), a.sqm(), a.amount()));
        }
        return dtos;
    }
}
