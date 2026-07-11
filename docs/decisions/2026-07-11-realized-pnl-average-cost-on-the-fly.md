# ADR: Realized P&L uses average-cost (PMP) and is computed on the fly

> Date: 2026-07-11
> Status: ✅ Active

## Context

Issue #38 asks to show gain/loss on **closed** positions. Nothing computed this today: a fully-sold
position is deleted by `HoldingComputeService` and only unrealized P&L on open holdings exists. Two
design questions had real alternatives: (1) which cost-basis method to realize gains against, and
(2) whether to persist the result or recompute it.

## Decision

Compute realized P&L **on the fly** from the ordered BUY/SELL transaction stream, using a
**moving-average (PMP) cost basis** (buy fees included in cost, sell fees netted from proceeds), in
the account's own currency. Expose it as its **own** read-only series
(`GET /api/accounts/{id}/realized-pnl`), never mixed into `pnl`/`rangePnl`. Nothing is persisted.

## Alternatives considered

### Cost basis: FIFO / specific-lot

- **Pros**: Matches some tax regimes (e.g. US) and per-lot reporting.
- **Cons**: The transaction stream carries **no lot identifiers**, so lots would be an arbitrary
  reconstruction. Diverges from the existing `averageBuyIn` (already average-cost) and from the
  French PEA convention (PMP).

### Persist realized gains in a table

- **Pros**: Cheap reads; a fixed audit trail.
- **Cons**: A second source of truth to keep in sync — every BUY/SELL/fee edit would have to
  invalidate/recompute it. Needs a migration and a backfill. Sync bugs waiting to happen.

## Reasoning

Average-cost is already the house convention (`HoldingComputeService.averageBuyIn`, `HoldingDedup`)
and the correct French PEA method, and it's the only method the data actually supports. Computing on
the fly mirrors the ratified `2026-04-26-loan-amortization-on-the-fly` ADR: the formula over the
source rows is the single source of truth, so any edit reflects on the next GET with no migration,
no cache, and no invalidation — a single ordered pass is cheap.

## Trade-offs accepted

- **Open PMP vs realized PMP can differ slightly.** Open holdings use a *global* VWAP over all buys;
  realized P&L uses a *moving* average recomputed at each sell. They coincide for a buy-then-sell
  sequence but diverge on interleaved buy→sell→buy. Accepted for consistency with the PEA method and
  the existing open-position cost basis.
- **No lot-level tax reporting** (no FIFO / specific-lot).
- Recomputed on every request (bounded: one pass over an account's BUY/SELL rows).

## Consequences

- New `RealizedPnlService` (no entity, no migration), `RealizedPnlResponse`, and a member-scoped GET.
- `HistoryService.buildPnl` is untouched — the separation-of-series invariant from
  `dashboard-liabilities-separation.md` holds.
- Over-sells clamp (no fabricated negative cost) and flag a warning rather than throwing.
- Feature note: [realized-pnl.md](../features/realized-pnl.md).
