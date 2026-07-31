package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.CryptoExchangePosition;
import com.picsou.port.CryptoExchangePort.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the delete-then-reinsert rewrite {@code CryptoExchangeSyncService.replacePositions} performs
 * on every sync, which needs a real persistence context to be meaningful at all.
 *
 * <p>The first shipped version used a derived {@code deleteByAccountId}. That only <em>queues</em>
 * removals, and Hibernate's action queue flushes inserts before deletes — so re-inserting the same
 * {@code (account, product, ticker)} in the same transaction hit the rows still in the table and
 * every sync after the first died on {@code uq_crypto_exchange_position}. The service test caught
 * nothing because its repository is a Mockito mock: no ORM, no constraint, no ordering.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/crypto-exchange-position-test-schema.sql")
class CryptoExchangePositionRepositoryTest {

    @Autowired
    CryptoExchangePositionRepository positionRepository;

    @Autowired
    TestEntityManager testEntityManager;

    private CryptoExchangePosition position(Account account, Product product, String ticker, String quantity) {
        return CryptoExchangePosition.builder()
            .account(account)
            .product(product)
            .ticker(ticker)
            .quantity(new BigDecimal(quantity))
            .build();
    }

    @Test
    void replacingAnAccountsPositionsInOneTransactionDoesNotTripTheUniqueConstraint() {
        // getReference(): the seed account row was inserted by raw SQL, outside JPA.
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        positionRepository.saveAll(List.of(
            position(account, Product.SPOT, "ETH", "0.5"),
            position(account, Product.STAKING, "ATOM", "33.154")));
        positionRepository.flush();

        // Exactly what a sync does: wipe the breakdown, then write the fresh one — same keys.
        assertThatCode(() -> {
            positionRepository.deleteAllForAccount(1L);
            positionRepository.saveAll(List.of(
                position(account, Product.SPOT, "ETH", "0.75"),
                position(account, Product.STAKING, "ATOM", "34.000")));
            positionRepository.flush();
        }).doesNotThrowAnyException();

        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L))
            .extracting(CryptoExchangePosition::getProduct, CryptoExchangePosition::getTicker)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple(Product.SPOT, "ETH"),
                org.assertj.core.api.Assertions.tuple(Product.STAKING, "ATOM"));
        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L))
            .extracting(CryptoExchangePosition::getQuantity)
            .allSatisfy(quantity -> assertThat(quantity).isNotEqualByComparingTo("0.5"));
    }

    @Test
    void aProductNoLongerHeldDisappears() {
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        positionRepository.saveAll(List.of(
            position(account, Product.SPOT, "ETH", "0.5"),
            position(account, Product.LENDING, "USDT", "75")));
        positionRepository.flush();

        // The user closed their lending contract: the rewrite must not leave the line behind.
        positionRepository.deleteAllForAccount(1L);
        positionRepository.saveAll(List.of(position(account, Product.SPOT, "ETH", "0.5")));
        positionRepository.flush();

        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L))
            .extracting(CryptoExchangePosition::getProduct)
            .containsExactly(Product.SPOT);
    }
}
