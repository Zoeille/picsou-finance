package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code V54__wallet_ethereum_to_evm.sql}, the one data-mutating migration in
 * the EVM fan-out change: it rewrites {@code wallet_address.chain} and, critically, the
 * {@code account.external_account_id} that ties a wallet to its synced account.
 *
 * <p>If the id rewrite were wrong or missing, {@code WalletSyncService} would compute a
 * {@code wallet_evm_<id>} key that matches nothing, silently create a <em>second</em>
 * account, and orphan the original — taking its balance-snapshot history and its
 * holdings' {@code average_buy_in} cost basis with it. That loss is invisible until a
 * user notices their net-worth chart restarted, so it is asserted here rather than
 * discovered in production.
 *
 * <p>Runs against real PostgreSQL via Testcontainers because the migration chain is
 * PostgreSQL-flavoured — {@code CREATE TYPE ... AS ENUM} and V54's own
 * {@code split_part()} do not exist in H2.
 */
@Testcontainers
class V54WalletEthereumToEvmMigrationTest {

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static Long ethWalletId;
    private static Long solWalletId;
    private static Long ethAccountId;
    private static Long solAccountId;
    private static Long bankAccountId;

    /**
     * Brings the schema to V53 (the state a deployed instance is in before this change),
     * seeds a realistic pre-migration dataset, then applies V54 alone.
     */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("53");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");

            // The wallet under migration, plus a Solana wallet as a negative control:
            // only ETHEREUM rows may be touched.
            ethWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('ETHEREUM', '0xc579D4Eb8179aF7f322F028D12BDDB845cA10a3b', " + memberId + ") RETURNING id");
            solWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('SOLANA', 'SoLaNaAddr', " + memberId + ") RETURNING id");

            ethAccountId = insertAccount(conn, memberId, "ETH Wallet", "wallet_ethereum_" + ethWalletId);
            solAccountId = insertAccount(conn, memberId, "SOL Wallet", "wallet_solana_" + solWalletId);
            // A bank account whose external id is unrelated: the LIKE filter must not reach it.
            bankAccountId = insertAccount(conn, memberId, "Checking", "gocardless_abc_123");

            // Cost basis on the migrated account -- the value most expensive to lose,
            // since it cannot be recomputed from on-chain data.
            exec(conn, "INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in) "
                + "VALUES (" + ethAccountId + ", 'ETH', 0.96100000, 1850.00000000)");
        }

        migrateTo("54");
    }

    @Test
    void convertsEthereumWalletToEvm() throws SQLException {
        assertThat(queryString("SELECT chain FROM wallet_address WHERE id = " + ethWalletId))
            .isEqualTo("EVM");
    }

    @Test
    void leavesOtherChainsUntouched() throws SQLException {
        assertThat(queryString("SELECT chain FROM wallet_address WHERE id = " + solWalletId))
            .isEqualTo("SOLANA");
    }

    @Test
    void rewritesExternalAccountId_keepingTheSameAccountRow() throws SQLException {
        // Same row id, new key: this is what keeps snapshots and holdings attached
        // instead of the next sync creating a fresh, empty account.
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo("wallet_evm_" + ethWalletId);
    }

    @Test
    void rewrittenIdMatchesWhatTheServiceWillCompute() throws SQLException {
        // WalletSyncService builds "wallet_" + chain.name().toLowerCase() + "_" + id.
        String chain = queryString("SELECT chain FROM wallet_address WHERE id = " + ethWalletId);
        String expected = "wallet_" + chain.toLowerCase() + "_" + ethWalletId;

        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo(expected);
    }

    @Test
    void preservesHoldingsAndCostBasis() throws SQLException {
        assertThat(queryLong("SELECT COUNT(*) FROM account_holding WHERE account_id = " + ethAccountId))
            .isEqualTo(1L);
        assertThat(queryBigDecimal(
            "SELECT average_buy_in FROM account_holding WHERE account_id = " + ethAccountId + " AND ticker = 'ETH'"))
            .isEqualByComparingTo("1850");
    }

    @Test
    void leavesUnrelatedExternalAccountIdsUntouched() throws SQLException {
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + solAccountId))
            .isEqualTo("wallet_solana_" + solWalletId);
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + bankAccountId))
            .isEqualTo("gocardless_abc_123");
    }

    @Test
    void isIdempotent_whenNoEthereumWalletsRemain() throws SQLException {
        // Re-running the migration's statements (a repair, a replayed deploy) must be a
        // no-op rather than mangling ids that already carry the evm_ prefix.
        try (Connection conn = connect()) {
            exec(conn, "UPDATE wallet_address SET chain = 'EVM' WHERE chain = 'ETHEREUM'");
            exec(conn, "UPDATE account SET external_account_id = 'wallet_evm_' "
                + "|| split_part(external_account_id, '_', 3) WHERE external_account_id LIKE 'wallet_ethereum_%'");
        }

        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo("wallet_evm_" + ethWalletId);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .outOfOrder(true) // mirrors application.yml
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertAccount(Connection conn, long memberId, String name, String externalId)
        throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, currency, current_balance, external_account_id, is_manual, member_id) "
                + "VALUES ('" + name + "', 'CRYPTO'::account_type, 'EUR', 100, '" + externalId + "', false, "
                + memberId + ") RETURNING id");
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static String queryString(String sql) throws SQLException {
        return querySingle(sql, rs -> rs.getString(1));
    }

    private static long queryLong(String sql) throws SQLException {
        return querySingle(sql, rs -> rs.getLong(1));
    }

    private static BigDecimal queryBigDecimal(String sql) throws SQLException {
        return querySingle(sql, rs -> rs.getBigDecimal(1));
    }

    private static <T> T querySingle(String sql, SqlFunction<T> extractor) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return extractor.apply(rs);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(ResultSet rs) throws SQLException;
    }
}
