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
     * Splits an institution id of the form "BankName::CC" (built by adapters' {@code
     * searchInstitutions}) into name and country parts. Splits at the LAST "::" — a bank name
     * could itself legitimately contain that substring, and the country is always the appended
     * final segment. {@code country()} is an empty string when absent/blank; callers decide their
     * own fallback (e.g. {@link #DEFAULT_COUNTRY} where a concrete country is required, or
     * treating blank as "unknown" for a broad, unfiltered institution search). A {@code null}
     * input returns an empty name and country rather than throwing — request-body validation
     * (see {@code SyncController.InitiateRequest}) is the primary guard against a missing id,
     * but this parser stays safe on its own regardless of caller diligence.
     */
    static ParsedInstitutionId parseInstitutionId(String institutionId) {
        if (institutionId == null) return new ParsedInstitutionId("", "");
        int sep = institutionId.lastIndexOf("::");
        String name = sep >= 0 ? institutionId.substring(0, sep) : institutionId;
        String country = sep >= 0 ? institutionId.substring(sep + 2) : "";
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

    record InstitutionData(
        String id,
        String name,
        String bic,
        String logoUrl,
        String country
    ) {}
}
