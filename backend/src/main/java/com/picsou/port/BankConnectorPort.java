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
