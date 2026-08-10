package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TransactionRepository#findByAccountIdAndTxTypeInOrderByDateAscIdAsc} feeds the
 * order-sensitive realized-P&L moving-average pass ({@code RealizedPnlService}) and the holdings
 * recompute pass ({@code HoldingComputeService}). {@code Transaction.date} is a day-granularity
 * {@code LocalDate}, so two same-day rows have undefined order under a {@code date}-only
 * ORDER BY. This test pins that {@code id} (insertion order) is a deterministic tiebreaker.
 *
 * <p>Flyway is disabled and the schema is hand-rolled (see
 * {@code sql/transaction-repository-test-schema.sql}): the real migrations are
 * PostgreSQL-flavoured and cannot run on H2 -- per docs/conventions/testing.md. This is the
 * first {@code @DataJpaTest} in the suite.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/transaction-repository-test-schema.sql")
class TransactionRepositoryTest {

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    TestEntityManager testEntityManager;

    @Test
    void sameDateRows_orderedByIdAfterDate_stableAcrossRepeatedCalls() {
        // A reference proxy (not a builder-constructed POJO): the seed account row was inserted
        // by the raw SQL script above, outside JPA, so Hibernate has never "seen" it as managed --
        // persisting a Transaction against a plain Account.builder().id(1L).build() would throw
        // TransientPropertyValueException. getReference() is the standard way to point a
        // @ManyToOne at a row known to exist without loading or re-persisting it.
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        LocalDate sameDate = LocalDate.of(2026, 7, 1);

        // Persist SELL first so it gets the lower id, despite BUY being the economically "earlier"
        // leg -- this is what proves the tiebreaker is insertion order (id), not transaction type.
        Transaction sell = transactionRepository.save(Transaction.builder()
            .account(account)
            .date(sameDate)
            .description("SELL")
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.SELL)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .build());

        Transaction buy = transactionRepository.save(Transaction.builder()
            .account(account)
            .date(sameDate)
            .description("BUY")
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.BUY)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .build());

        testEntityManager.flush();
        assertThat(sell.getId()).isLessThan(buy.getId());

        List<TransactionType> types = List.of(TransactionType.BUY, TransactionType.SELL);

        // Called twice: not a coincidence of one lucky plan, but the guaranteed order the
        // ORDER BY (date, id) clause produces every time.
        List<Transaction> first = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(1L, types);
        List<Transaction> second = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(1L, types);

        assertThat(first).extracting(Transaction::getId).containsExactly(sell.getId(), buy.getId());
        assertThat(second).extracting(Transaction::getId).containsExactly(sell.getId(), buy.getId());
    }
}
