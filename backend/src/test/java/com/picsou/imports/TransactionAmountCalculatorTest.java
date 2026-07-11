package com.picsou.imports;

import com.picsou.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionAmountCalculatorTest {

    @Test
    void buy_isNegativeGrossPlusFees() {
        // -(10 * 85.20 + 1.00) = -853.00 — a BUY is a cash outflow and fees add to the cost.
        BigDecimal amount = TransactionAmountCalculator.signedAmount(
            TransactionType.BUY, new BigDecimal("10"), new BigDecimal("85.20"), new BigDecimal("1.00"));
        assertThat(amount).isEqualByComparingTo("-853.00");
    }

    @Test
    void sell_isPositiveGrossMinusFees() {
        // +(10 * 92.50 - 1.00) = 924.00 — a SELL is a cash inflow, net of fees.
        BigDecimal amount = TransactionAmountCalculator.signedAmount(
            TransactionType.SELL, new BigDecimal("10"), new BigDecimal("92.50"), new BigDecimal("1.00"));
        assertThat(amount).isEqualByComparingTo("924.00");
    }

    @Test
    void nullFees_treatedAsZero() {
        assertThat(TransactionAmountCalculator.signedAmount(
            TransactionType.BUY, new BigDecimal("2"), new BigDecimal("50"), null))
            .isEqualByComparingTo("-100");
        assertThat(TransactionAmountCalculator.signedAmount(
            TransactionType.SELL, new BigDecimal("2"), new BigDecimal("50"), null))
            .isEqualByComparingTo("100");
    }

    @Test
    void nullQuantityOrPrice_treatedAsZero() {
        assertThat(TransactionAmountCalculator.signedAmount(
            TransactionType.BUY, null, new BigDecimal("50"), new BigDecimal("2")))
            .isEqualByComparingTo("-2");   // -(0 + 2)
        assertThat(TransactionAmountCalculator.signedAmount(
            TransactionType.SELL, new BigDecimal("3"), null, new BigDecimal("1")))
            .isEqualByComparingTo("-1");   // +(0 - 1)
    }

    @Test
    void buyAmount_negated_equalsVwapCostContribution() {
        // Cross-check the signing seam vs the PMP numerator: -(buyAmount) == qty*price + fees.
        BigDecimal qty = new BigDecimal("7");
        BigDecimal price = new BigDecimal("123.45");
        BigDecimal fees = new BigDecimal("3.30");
        BigDecimal buyAmount = TransactionAmountCalculator.signedAmount(TransactionType.BUY, qty, price, fees);
        assertThat(buyAmount.negate()).isEqualByComparingTo(qty.multiply(price).add(fees));
    }
}
