package com.picsou.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates every JPA entity against the real, Flyway-migrated PostgreSQL schema.
 *
 * <p><b>Why this exists.</b> The rest of the suite runs on H2, which accepts mappings
 * PostgreSQL rejects — so an entity can drift from its migration while every test passes, and
 * the application then refuses to boot. That is not hypothetical: it was added after
 * {@code country CHAR(2)} shipped through a green build and failed startup with <em>"wrong
 * column type encountered in column [country]: found [bpchar], but expecting [varchar(2)]"</em>.
 * Flyway had already applied the migration by then, so the container crash-looped against an
 * upgraded database.
 *
 * <p>Booting the persistence unit with {@code ddl-auto=validate} against real PostgreSQL runs
 * the same check the application runs at startup, across every entity at once.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIf("dockerAvailable")
class SchemaMappingValidationTest {

    static {
        // Same reason as WalletEvmMigrationTest: docker-java negotiates down to an API version
        // modern engines refuse, which is indistinguishable from "no Docker".
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The schema-mapping test cannot be skipped -- it is the only check that "
                    + "entities match the PostgreSQL schema. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Mirror production: Flyway owns the schema, Hibernate only checks it. Loosening
        // ddl-auto here would silently disable the entire point of this test.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.out-of-order", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void everyEntityMatchesTheMigratedSchema() {
        // Reaching this point is the assertion: building the EntityManagerFactory under
        // ddl-auto=validate throws SchemaManagementException on the first mismatched column,
        // so the context would have failed before the test body ran.
        assertThat(entityManagerFactory).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isNotEmpty();
    }
}
