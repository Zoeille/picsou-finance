package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("postgresAvailable")
class PersistentSessionTokenVersionMigrationTest {

    private static final String SCHEMA = "persistent_session_v82_migration_test";
    private static final String EXTERNAL_JDBC_URL = System.getenv("PICSOU_TEST_POSTGRES_URL");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    static boolean postgresAvailable() {
        if (hasExternalDatabase()) {
            return true;
        }
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found.");
        }
        return available;
    }

    @BeforeAll
    static void startPostgres() {
        if (!hasExternalDatabase()) {
            POSTGRES.start();
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (!hasExternalDatabase() && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void backfillsEveryExistingSessionWithItsOwnersTokenVersion() throws Exception {
        dropTestSchema();
        try {
            migrateTo("79");
            seedPreMigrationSessions();
            migrateTo("82");

            try (Connection connection = connect()) {
                setSearchPath(connection);
                assertThat(readSessions(connection)).containsExactly(
                    new SessionEpoch("00000000-0000-0000-0000-000000000001", 7L, false),
                    new SessionEpoch("00000000-0000-0000-0000-000000000002", 7L, true),
                    new SessionEpoch("00000000-0000-0000-0000-000000000003", 0L, false));
                assertThat(queryColumnNullability(connection)).isEqualTo("NO");
            }
        } finally {
            dropTestSchema();
        }
    }

    private static void seedPreMigrationSessions() throws SQLException {
        try (Connection connection = connect()) {
            setSearchPath(connection);
            long firstMemberId = insertReturningId(connection,
                "INSERT INTO family_member (display_name) VALUES (?) RETURNING id", "Version Seven");
            long secondMemberId = insertReturningId(connection,
                "INSERT INTO family_member (display_name) VALUES (?) RETURNING id", "Version Zero");
            long firstUserId = insertReturningId(connection,
                "INSERT INTO app_user (username, password_hash, member_id, token_version) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                "version-seven", "hash-seven", firstMemberId, 7L);
            long secondUserId = insertReturningId(connection,
                "INSERT INTO app_user (username, password_hash, member_id, token_version) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                "version-zero", "hash-zero", secondMemberId, 0L);

            execute(connection,
                "INSERT INTO persistent_session (series_id, token_hash, user_id, expires_at) "
                    + "VALUES (?::uuid, ?, ?, now() + interval '90 days')",
                "00000000-0000-0000-0000-000000000001", "active-seven", firstUserId);
            execute(connection,
                "INSERT INTO persistent_session "
                    + "(series_id, token_hash, user_id, expires_at, revoked_at) "
                    + "VALUES (?::uuid, ?, ?, now() + interval '90 days', now())",
                "00000000-0000-0000-0000-000000000002", "revoked-seven", firstUserId);
            execute(connection,
                "INSERT INTO persistent_session (series_id, token_hash, user_id, expires_at) "
                    + "VALUES (?::uuid, ?, ?, now() + interval '90 days')",
                "00000000-0000-0000-0000-000000000003", "active-zero", secondUserId);
        }
    }

    private static List<SessionEpoch> readSessions(Connection connection) throws SQLException {
        List<SessionEpoch> sessions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT series_id::text, token_version, revoked_at IS NOT NULL "
                + "FROM persistent_session ORDER BY series_id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                sessions.add(new SessionEpoch(
                    result.getString(1), result.getLong(2), result.getBoolean(3)));
            }
        }
        return sessions;
    }

    private static String queryColumnNullability(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = 'persistent_session'
              AND column_name = 'token_version'
            """)) {
            statement.setString(1, SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static long insertReturningId(
        Connection connection,
        String sql,
        Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(
        Connection connection,
        String sql,
        Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(
        Connection connection,
        String sql,
        Object... parameters
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(jdbcUrl(), username(), password())
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .target(version)
            .outOfOrder(true)
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    private static void setSearchPath(Connection connection) throws SQLException {
        connection.setSchema(SCHEMA);
    }

    private static void dropTestSchema() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                 "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE")) {
            statement.execute();
        }
    }

    private static boolean hasExternalDatabase() {
        return EXTERNAL_JDBC_URL != null && !EXTERNAL_JDBC_URL.isBlank();
    }

    private static String jdbcUrl() {
        return hasExternalDatabase() ? EXTERNAL_JDBC_URL : POSTGRES.getJdbcUrl();
    }

    private static String username() {
        return hasExternalDatabase()
            ? System.getenv().getOrDefault("PICSOU_TEST_POSTGRES_USERNAME", "postgres")
            : POSTGRES.getUsername();
    }

    private static String password() {
        return hasExternalDatabase()
            ? System.getenv().getOrDefault("PICSOU_TEST_POSTGRES_PASSWORD", "")
            : POSTGRES.getPassword();
    }

    private record SessionEpoch(String seriesId, long tokenVersion, boolean revoked) {
    }
}
