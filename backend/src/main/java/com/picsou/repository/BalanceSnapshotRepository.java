package com.picsou.repository;

import com.picsou.model.BalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, Long> {

    List<BalanceSnapshot> findByAccountIdAndDateBetweenOrderByDateAsc(
        Long accountId, LocalDate from, LocalDate to
    );

    Optional<BalanceSnapshot> findByAccountIdAndDate(Long accountId, LocalDate date);

    /** Latest snapshot per account for dashboard aggregate */
    @Query("""
        SELECT s FROM BalanceSnapshot s
        WHERE s.account.id = :accountId
          AND s.date = (SELECT MAX(s2.date) FROM BalanceSnapshot s2 WHERE s2.account.id = :accountId)
        """)
    Optional<BalanceSnapshot> findLatestByAccountId(@Param("accountId") Long accountId);

    /** Aggregate daily net worth: sum of all account balances per day */
    @Query(value = """
        SELECT s.date, SUM(s.balance)
        FROM balance_snapshot s
        WHERE s.date >= :from
        GROUP BY s.date
        ORDER BY s.date ASC
        """, nativeQuery = true)
    List<Object[]> findDailyNetWorth(@Param("from") LocalDate from);

    /** Last 3 months of snapshots for a given account (for monthly contribution calc) */
    @Query("""
        SELECT s FROM BalanceSnapshot s
        WHERE s.account.id = :accountId
          AND s.date >= :from
        ORDER BY s.date ASC
        """)
    List<BalanceSnapshot> findRecentByAccountId(
        @Param("accountId") Long accountId,
        @Param("from") LocalDate from
    );
}
