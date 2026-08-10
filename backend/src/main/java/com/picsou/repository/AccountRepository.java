package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findAllByMemberIdOrderByCreatedAtAsc(Long memberId);
    Optional<Account> findByIdAndMemberId(Long id, Long memberId);

    /**
     * Member-scoped batch lookup by id. Used when resolving a caller-supplied list of
     * account ids (e.g. goal membership) so accounts belonging to another member are
     * never returned — closing an IDOR where {@code findAllById} ignored ownership.
     */
    List<Account> findByIdInAndMemberId(List<Long> ids, Long memberId);
    Optional<Account> findByExternalAccountIdAndMemberId(String externalAccountId, Long memberId);

    /**
     * Account-level tickers of live accounts of one type, for the hourly price warm-up — see
     * {@code SchedulerService}.
     *
     * <p>A projection rather than {@code findAll()}: one column is read, and loading every account
     * entity every hour to reach it is waste. The type split is what keeps a crypto account's own
     * ticker away from Yahoo Finance, which would answer with the share price of the equity
     * trading under the same symbol and record it in {@code price_snapshot}. Blank tickers are
     * excluded here so no caller has to remember to. {@code Account}'s {@code @SQLRestriction}
     * would cover the soft-delete predicate on its own; it is written out anyway so the intent
     * survives a refactor of that annotation.
     */
    @Query("""
        SELECT DISTINCT a.ticker FROM Account a
        WHERE a.ticker IS NOT NULL AND LENGTH(TRIM(a.ticker)) > 0
          AND a.deletedAt IS NULL AND a.type = :type
        """)
    Set<String> findDistinctTickersByType(@Param("type") AccountType type);

    /** {@link #findDistinctTickersByType} inverted: every live account that is <em>not</em> of that type. */
    @Query("""
        SELECT DISTINCT a.ticker FROM Account a
        WHERE a.ticker IS NOT NULL AND LENGTH(TRIM(a.ticker)) > 0
          AND a.deletedAt IS NULL AND a.type <> :type
        """)
    Set<String> findDistinctTickersExcludingType(@Param("type") AccountType type);

    /**
     * Returns true if any soft-deleted account exists with this external id for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by sync upserts to refuse resurrecting accounts the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE external_account_id = :externalId AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByExternalAccountIdAndMemberId(
        @Param("externalId") String externalId,
        @Param("memberId") Long memberId
    );
}
