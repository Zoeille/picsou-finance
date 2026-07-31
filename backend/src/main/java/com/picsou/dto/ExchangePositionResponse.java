package com.picsou.dto;

import java.math.BigDecimal;

/**
 * One line of a crypto exchange account's per-product breakdown.
 *
 * @param principal the capital part of {@code quantity}, null when the exchange doesn't split it
 * @param interest  yield <em>already included</em> in {@code quantity}, never an addition to it
 */
public record ExchangePositionResponse(
    String product,
    String ticker,
    BigDecimal quantity,
    BigDecimal principal,
    BigDecimal interest,
    BigDecimal currentPriceEur,   // null when the asset has no CoinGecko mapping
    BigDecimal currentValueEur    // null when the price is unknown
) {}
