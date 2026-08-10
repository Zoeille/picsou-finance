package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Port for bank account synchronization providers.
 * Implement this interface to add a new bank connector (e.g. Plaid, Powens).
 */
public interface BankConnectorPort {

    /** Fallback country when none is specified — Picsou's primary market. */
    String DEFAULT_COUNTRY = "FR";

    /** Create an authorization link to connect a bank account. */
    InitiateResult initiateConnection(String institutionId);

    /** Exchange the OAuth code from the callback for a session ID. */
    String exchangeCode(String oauthCode);

    /** Fetch balances for all accounts linked to this session. */
    List<AccountData> fetchBalances(String sessionId);

    /** Search institutions by name/country. */
    List<InstitutionData> searchInstitutions(String query, String country);

    /** Distinct country codes this provider has institutions for, for a "which country" selector. */
    List<String> listCountries();

    /**
     * Splits an institution id built by an adapter's {@code searchInstitutions} into its
     * name and country parts. Two shapes exist (see {@code docs/features/bank-sync.md}):
     * the current {@code "BankName::CC::psuType"} and the legacy {@code "BankName::CC"}
     * written before PSU types were modelled. The country is therefore the SECOND segment
     * in both — not the last one, which would resolve to {@code "business"}/{@code
     * "personal"} for every current id — so this splits at the FIRST "::" for the name and
     * takes what follows up to the next "::" as the country.
     *
     * <p>{@code country()} is an empty string when absent/blank; callers decide their own
     * fallback (e.g. {@link #DEFAULT_COUNTRY} where a concrete country is required, or
     * treating blank as "unknown" for a broad, unfiltered institution search). A {@code null}
     * input returns an empty name and country rather than throwing — request-body validation
     * (see {@code SyncController.InitiateRequest}) is the primary guard against a missing id,
     * but this parser stays safe on its own regardless of caller diligence.
     */
    static ParsedInstitutionId parseInstitutionId(String institutionId) {
        if (institutionId == null) return new ParsedInstitutionId("", "");
        int sep = institutionId.indexOf("::");
        if (sep < 0) return new ParsedInstitutionId(institutionId, "");
        String name = institutionId.substring(0, sep);
        String rest = institutionId.substring(sep + 2);
        int next = rest.indexOf("::");
        String country = next >= 0 ? rest.substring(0, next) : rest;
        return new ParsedInstitutionId(name, country);
    }

    record ParsedInstitutionId(String name, String country) {}

    record InitiateResult(String requisitionId, String authLink) {}

    record AccountData(
        String externalId,
        String name,
        String iban,
        String currency,
        BigDecimal balance
    ) {}

    /**
     * @param id       opaque round-trip token; the Enable Banking adapter encodes
     *                 {@code name::country::psuType} in it (see the adapter's
     *                 {@code parseInstitutionId}). Clients must pass it back verbatim.
     * @param psuType  {@code "personal"} or {@code "business"} — which login the
     *                 provider will present. Banks serving only professionals
     *                 (Swan, Qonto…) are published under {@code business} only.
     */
    record InstitutionData(
        String id,
        String name,
        String bic,
        String logoUrl,
        String country,
        String psuType
    ) {}
}
