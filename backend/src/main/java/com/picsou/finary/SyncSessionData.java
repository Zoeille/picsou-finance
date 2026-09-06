package com.picsou.finary;

import com.picsou.finary.client.FinaryApiClient.OrgContext;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryTransactionDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Holds all data fetched during the preview phase of an API sync.
 * Cached in memory with a UUID key (syncToken) until execute is called or timeout.
 */
public record SyncSessionData(
    Long memberId,
    List<CategorizedFinaryAccount> allAccounts,
    Map<String, List<FinaryTransactionDto>> transactionsByCategory,
    Instant createdAt
) {}
