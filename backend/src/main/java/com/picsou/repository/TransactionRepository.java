package com.picsou.repository;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByDateDesc(Long accountId);

    /** Bulk-update the merchant label for all transactions of an account (pocket rename). */
    @Modifying
    @Query("UPDATE Transaction t SET t.merchantLabel = :label WHERE t.account.id = :accountId")
    void updateMerchantLabelByAccountId(@Param("accountId") Long accountId, @Param("label") String label);

    /**
     * Bulk-update the merchant label for all wallet transactions whose description matches
     * the pocket's UUID pattern (wallet side of pocket transfers, e.g. "To EUR MB:<uuid>").
     */
    @Modifying
    @Query("""
        UPDATE Transaction t SET t.merchantLabel = :label
        WHERE t.account.id = :walletAccountId
        AND LOWER(t.description) LIKE LOWER(CONCAT('%mb:', :pocketUuid))
        """)
    void updateMerchantLabelForPocketWalletSide(
        @Param("walletAccountId") Long walletAccountId,
        @Param("pocketUuid") String pocketUuid,
        @Param("label") String label);

    Optional<Transaction> findByIdAndAccountId(Long id, Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndIsManualFalse(Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :date")
    BigDecimal sumAmountByAccountIdAndDateAfter(@Param("accountId") Long accountId, @Param("date") LocalDate date);

    List<Transaction> findByAccountIdAndTxTypeInOrderByDateAsc(Long accountId, List<TransactionType> types);

    /** Earliest transaction date across all accounts */
    @Query("SELECT MIN(t.date) FROM Transaction t")
    LocalDate findEarliestDate();

    // ─── Budget & Cashflow (1.1.0) ────────────────────────────────────────────

    /** Dedup guard for synced ingestion (account-scoped). */
    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    /** Member-scoped single transaction lookup (categorize endpoint). */
    Optional<Transaction> findByIdAndAccountMemberId(Long id, Long memberId);

    /** Member-scoped batch lookup by id set — used by the async AI job. */
    List<Transaction> findAllByIdInAndAccountMemberId(Collection<Long> ids, Long memberId);

    /** Transactions belonging to a member that have no managed category yet. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef IS NULL
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findUncategorizedByMemberId(@Param("memberId") Long memberId);

    /**
     * The member's most recent hand/auto-categorized, labelled transactions — used as few-shot
     * examples for the AI categorizer so it learns the member's taxonomy from their own history.
     * Pass a {@code Pageable} (e.g. {@code PageRequest.of(0, 8)}) to bound the count.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef IS NOT NULL AND t.merchantLabel IS NOT NULL
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findRecentCategorizedByMemberId(@Param("memberId") Long memberId,
                                                      org.springframework.data.domain.Pageable pageable);

    /** All member transactions in a date range (cashflow / detection input). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.date BETWEEN :from AND :to
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByMemberIdAndDateBetween(@Param("memberId") Long memberId,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);

    /** Cross-account search with optional account and category filters, newest first. */
    @Query("""
        SELECT t FROM Transaction t
        JOIN FETCH t.account
        LEFT JOIN FETCH t.categoryRef
        WHERE t.account.member.id = :memberId
        AND t.date BETWEEN :from AND :to
        AND (:accountId IS NULL OR t.account.id = :accountId)
        AND (:categoryId IS NULL OR t.categoryRef.id = :categoryId)
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> searchByMember(@Param("memberId") Long memberId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("accountId") Long accountId,
                                     @Param("categoryId") Long categoryId);

    /** One category's transactions for a member over a range, newest first (spending drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id = :categoryId
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdAndDateBetween(@Param("memberId") Long memberId,
                                                                @Param("categoryId") Long categoryId,
                                                                @Param("from") LocalDate from,
                                                                @Param("to") LocalDate to);

    /** Sum of (signed) amounts for one category over a date range — used by envelopes. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id = :categoryId AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdAndDateBetween(@Param("categoryId") Long categoryId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** A member's transactions across several categories over a range, newest first (parent drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id IN :categoryIds
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdInAndDateBetween(@Param("memberId") Long memberId,
                                                                  @Param("categoryIds") Collection<Long> categoryIds,
                                                                  @Param("from") LocalDate from,
                                                                  @Param("to") LocalDate to);

    /** Sum of (signed) amounts across several categories over a range — parent envelope rollup. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id IN :categoryIds AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdInAndDateBetween(@Param("categoryIds") Collection<Long> categoryIds,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** Sum of (signed) amounts for a member, filtered by category kind, over a range. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                  @Param("kind") com.picsou.model.CategoryKind kind,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** Member transactions with a given category kind in a range (allocation flux). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    List<Transaction> findByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                          @Param("kind") com.picsou.model.CategoryKind kind,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    /** Detach all transactions from a category before it is hard-removed/archived. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Transaction t SET t.categoryRef = NULL WHERE t.categoryRef.id = :categoryId")
    void clearCategory(@Param("categoryId") Long categoryId);

    // ─── Rule preview ────────────────────────────────────────────────────────

    /**
     * All member transactions that could be changed by a new rule:
     * those without a category or with a non-manual (auto/brand/AI) category assignment.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId
          AND (t.categoryRef IS NULL OR t.categoryManual = false)
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findChangeable(@Param("memberId") Long memberId);

    // ─── Savings livrets (1.1.0) ──────────────────────────────────────────────

    /**
     * All transactions for a specific account in a date range, oldest first.
     * Used by the savings interest projection engine to replay the capital history.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.date BETWEEN :from AND :to
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByAccountIdAndDateBetweenOrderByDateAsc(
        @Param("accountId") Long accountId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    // ─── Revolut pockets (1.1.0) ─────────────────────────────────────────────

    /**
     * All transactions for an account ordered by date (oldest first), used for backfill.
     * Member-scoped via account ownership.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByAccountIdOrderByDateAsc(@Param("accountId") Long accountId);

    /**
     * Transactions for a specific pocket account (by id), newest first.
     * Used by the unnamed-pockets listing to show recent inflows.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findTopByAccountIdOrderByDateDesc(
        @Param("accountId") Long accountId,
        org.springframework.data.domain.Pageable pageable
    );
}
