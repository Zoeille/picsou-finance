package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Realized profit/loss on an investment account, computed on the fly from the ordered BUY/SELL
 * stream using the moving-average (PMP) cost basis — buy fees are part of the cost, sell fees
 * reduce the proceeds. This is a strictly separate series from the unrealized {@code PnlResponse.pnl}.
 * All amounts are in the account's own currency (no re-pricing / FX).
 *
 * @param realizedTotal sum of realized gains/losses across every sell
 * @param byTicker      per-security aggregates ({@code warning} true if a sell exceeded the held qty)
 * @param lots          one entry per sell, in chronological order
 */
public record RealizedPnlResponse(
    String currency,
    BigDecimal realizedTotal,
    List<TickerRealized> byTicker,
    List<ClosedLot> lots
) {
    public record TickerRealized(
        String ticker,
        String name,
        BigDecimal realized,
        BigDecimal quantitySold,
        BigDecimal proceeds,
        BigDecimal costBasis,
        boolean warning
    ) {}

    public record ClosedLot(
        String ticker,
        String name,
        LocalDate date,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal proceeds,
        BigDecimal realized
    ) {}
}
