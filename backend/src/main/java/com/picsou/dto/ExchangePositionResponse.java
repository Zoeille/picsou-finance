package com.picsou.dto;

import java.math.BigDecimal;

/**
 * One line of a crypto exchange account's per-product breakdown.
 *
 * @param principal    the capital part of {@code quantity}, null when the exchange doesn't split it
 * @param interest     yield <em>already included</em> in {@code quantity}, never an addition to it
 * @param averageBuyIn the asset's unit cost basis, taken from its {@code AccountHolding} — cost is
 *                     tracked per asset, not per product, so every line of the same asset shares
 *                     it and the per-line figures still add up to the holding's
 * @param costBasisEur {@code averageBuyIn × quantity}, i.e. this line's share of that cost
 */
public record ExchangePositionResponse(
    String product,
    String ticker,
    BigDecimal quantity,
    BigDecimal principal,
    BigDecimal interest,
    BigDecimal averageBuyIn,      // null when the asset has no recorded cost basis
    BigDecimal currentPriceEur,   // null when the asset has no CoinGecko mapping
    BigDecimal currentValueEur,   // null when the price is unknown
    BigDecimal costBasisEur,
    BigDecimal pnlEur,
    BigDecimal pnlPercent
) {}
