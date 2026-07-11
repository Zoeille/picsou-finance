# Feature: Realized P&L on closed positions

> Last updated: 2026-07-11

## Context

The portfolio view only shows **open** holdings, so once a position is fully sold it disappears and
its gain/loss is invisible — you could only infer it from the raw transaction list
([issue #38](https://github.com/Zoeille/picsou-finance/issues/38)). This adds a realized P&L series
computed from the transaction stream, surfaced as a "realized gains" section on the account page.

## How it works

`RealizedPnlService.compute(accountId, memberId)` walks the account's BUY/SELL transactions in date
order and maintains a **moving-average (PMP) cost basis** per ticker:

- **BUY**: `costPool += qty·price + fees`, `qtyHeld += qty` (buy fees are part of the cost).
- **SELL**: `avgCost = costPool / qtyHeld`; `proceeds = qty·price − fees`;
  `realized = proceeds − avgCost·qtySold`; then draw the pool down by `avgCost·qtySold`.

An over-sell (selling more than is held, or with no prior buy) clamps the costed quantity to what's
held — no fabricated negative cost — and flags a per-ticker `warning`. Everything is in the
**account's own currency**; there is **no re-pricing / FX**, so the numbers can't drift with market
data. Nothing is persisted — the transaction stream is the source of truth (same posture as
`LoanAmortizationService`).

### Key files

- `backend/src/main/java/com/picsou/service/RealizedPnlService.java` — the moving-average pass.
- `backend/src/main/java/com/picsou/dto/RealizedPnlResponse.java` — `currency`, `realizedTotal`,
  `byTicker[]`, `lots[]`.
- `AccountController#getRealizedPnl` — `GET /api/accounts/{id}/realized-pnl` (member-scoped, read-only).
- `frontend/src/components/shared/RealizedPnlSection.tsx` — closed-lot table + green/red total.
- `frontend/src/features/accounts/hooks.ts#useRealizedPnl`.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Moving-average (PMP) cost | Matches French PEA convention and the existing `averageBuyIn` | FIFO / specific-lot (no lot ids in the stream) |
| Compute on the fly | Stream is the source of truth; any edit reflects on next GET; no migration/invalidation | Persist a `realized_gain` table |
| Own series, separate endpoint | The `pnl`/`rangePnl` series are unrealized and debt-neutral by invariant | Fold into `buildPnl` |
| Account currency, no re-pricing | Prices are EUR-only and skipped-never-fabricated; historical proceeds are known | Re-price with live/FX rates |

See ADR [2026-07-11-realized-pnl-average-cost-on-the-fly](../decisions/2026-07-11-realized-pnl-average-cost-on-the-fly.md).

## Gotchas / Pitfalls

- **`HoldingComputeService` deletes a holding when net qty ≤ 0.** Realized P&L reads the transaction
  stream, so it still computes for a position that no longer has a holding row — that is exactly the
  data this feature recovers.
- **Open PMP vs realized PMP can differ slightly.** Open holdings use a *global* VWAP over all buys
  (`HoldingComputeService`), while realized P&L uses a *moving* average recomputed at each sell. They
  coincide for a buy-then-sell sequence but diverge on interleaved buy→sell→buy. Accepted; documented
  in the ADR.
- **Do not re-mix realized into `pnl`.** Per [dashboard-liabilities-separation.md](dashboard-liabilities-separation.md),
  performance series stay distinct.
- The frontend guards `data.lots` (demo mode returns `{}` for unhandled endpoints).

## Tests

- `RealizedPnlServiceTest` — partial/full close, fee folding, over-sell (clamp + warning),
  sell-without-buy, account currency, buys-only (empty).
- `RealizedPnlSection.test.tsx` — green/red total, hidden when no lots.

## Links

- ADR: [realized-pnl-average-cost-on-the-fly](../decisions/2026-07-11-realized-pnl-average-cost-on-the-fly.md)
- [live-prices-holdings.md](live-prices-holdings.md) · [dashboard-liabilities-separation.md](dashboard-liabilities-separation.md)
- Ticket: [issue #38](https://github.com/Zoeille/picsou-finance/issues/38)
