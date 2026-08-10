-- Minimal H2 schema for TransactionRepositoryTest (@DataJpaTest).
--
-- The real schema comes from Flyway (backend/src/main/resources/db/migration), which is
-- PostgreSQL-flavoured (native enum types via CREATE TYPE ... AS ENUM, etc.) and cannot run on
-- H2 -- see docs/conventions/testing.md. This script stands up just enough structure for
-- TransactionRepository's derived-query ordering to be exercised: an `account` row to satisfy
-- the FK on `transaction.account_id`, and a `transaction` table with every column the
-- Transaction entity maps (Hibernate includes all mapped columns in generated INSERTs
-- regardless of what a given test cares about).
--
-- `account` deliberately carries only `id` and `deleted_at`: this test never persists or queries
-- the Account entity through JPA (see getReference() usage in the test), only via this seed row,
-- so it never touches Account's Postgres-only `account_type` native-enum column. `deleted_at` is
-- required despite that: Account carries a class-level @SQLRestriction("deleted_at IS NULL"),
-- which Hibernate folds into the JOIN ON clause for *any* query that traverses the `account`
-- association -- including a plain `t.account.id` path in a derived query -- so the column must
-- exist even though this test never sets it (NULL, matching the restriction).

CREATE TABLE account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    deleted_at TIMESTAMP
);

INSERT INTO account (id) VALUES (1);

CREATE TABLE transaction (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT          NOT NULL,
    date            DATE            NOT NULL,
    description     VARCHAR(255)    NOT NULL,
    amount          DECIMAL(20, 8)  NOT NULL,
    type            VARCHAR(100),
    category        VARCHAR(100),
    native_currency VARCHAR(10)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    is_manual       BOOLEAN         NOT NULL,
    tx_type         VARCHAR(20),
    ticker          VARCHAR(30),
    name            VARCHAR(100),
    quantity        DECIMAL(20, 8),
    price_per_unit  DECIMAL(20, 8),
    fees            DECIMAL(20, 8)
);
