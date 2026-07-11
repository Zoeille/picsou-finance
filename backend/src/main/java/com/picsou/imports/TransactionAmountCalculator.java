package com.picsou.imports;

import com.picsou.model.TransactionType;

import java.math.BigDecimal;

/**
 * Single source of truth for signing a BUY/SELL transaction's stored {@code amount}
 * from its quantity/price/fees. Shared by every writer of investment transactions —
 * today the CSV importer, and mirrored (not shared, since it's TypeScript) by the
 * manual-entry frontend modal — so the sign convention never drifts between paths.
 *
 * <p>Convention: BUY is a cash outflow, SELL is a cash inflow.
 * <ul>
 *   <li>BUY:  {@code amount = -(quantity * pricePerUnit + fees)}</li>
 *   <li>SELL: {@code amount = +(quantity * pricePerUnit - fees)}</li>
 * </ul>
 *
 * <p>{@code null} quantity, price or fees are treated as zero, matching the
 * null-as-zero convention used throughout {@code HoldingComputeService}. No
 * rounding is applied beyond what {@link BigDecimal} multiplication/addition
 * yields — the {@code amount} column is {@code NUMERIC(20,8)}, so callers may
 * still want to scale the result before persisting, but this helper never
 * truncates precision itself.
 */
public final class TransactionAmountCalculator {

    private TransactionAmountCalculator() {
    }

    public static BigDecimal signedAmount(TransactionType side, BigDecimal quantity, BigDecimal pricePerUnit, BigDecimal fees) {
        BigDecimal qty = quantity != null ? quantity : BigDecimal.ZERO;
        BigDecimal price = pricePerUnit != null ? pricePerUnit : BigDecimal.ZERO;
        BigDecimal safeFees = fees != null ? fees : BigDecimal.ZERO;
        BigDecimal gross = qty.multiply(price);

        if (side == TransactionType.SELL) {
            return gross.subtract(safeFees);
        }
        // BUY (and any other side, defensively) is treated as an outflow.
        return gross.add(safeFees).negate();
    }
}
