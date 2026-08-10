package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Port for bank account synchronization providers.
 * Implement this interface to add a new bank connector (e.g. Plaid, Powens).
 */
public interface BankConnectorPort {

    /** Create an authorization link to connect a bank account. */
    InitiateResult initiateConnection(String institutionId);

    /** Exchange the OAuth code from the callback for a session ID. */
    String exchangeCode(String oauthCode);

    /** Fetch balances for all accounts linked to this session. */
    List<AccountData> fetchBalances(String sessionId);

    /** Search institutions by name/country. */
    List<InstitutionData> searchInstitutions(String query, String country);

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
