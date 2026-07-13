# Feature: Accounts Overview (PnL chart + summary card + asset type filters)

> Last updated: 2026-04-13

## Context

The Accounts page (`/accounts`) shows a grid of account cards. Users need a visual overview of PnL evolution over time, with the ability to filter by asset type (stocks, savings, crypto, etc.). A summary card at top shows the total balance and PnL for the current filter, similar to the Dashboard hero card.

## How it works

### Summary card

A `Card` at the top of the page shows the total balance for the filtered accounts. If the current filter contains investment accounts (PEA, COMPTE_TITRES, CRYPTO), it also displays the aggregate PnL (total balance - total invested) with a green/red trend icon and percentage, using the same style as the Dashboard net worth card.

PnL values come from the `invested` dataset in `useAllAccountsHistory` — the last point's invested amounts are summed for all filtered accounts.

### PnL line chart

The `AccountsStackedChart` renders a Recharts `LineChart` (not stacked — PnL can be negative). Each line represents one category or account's PnL (`balance - invested`) over time.

Data preparation depends on the active filter:

- **ALL filter**: PnL is aggregated by asset type group (one line per group: STOCKS, CRYPTO, etc.).
- **Specific filter** (e.g. STOCKS): PnL is computed per individual account.

The chart is only rendered when `hasHoldings` is true (current filter contains investment accounts). For cash-only filters (SAVINGS, CHECKING), the chart is hidden since PnL is always 0.

### Asset type filters

Six asset categories defined in `AccountsPage.tsx`:

| Filter key | Account types | Chart color |
|-----------|--------------|-------------|
| STOCKS | PEA, COMPTE_TITRES | `#6366f1` |
| METALS | OTHER | `#eab308` |
| SAVINGS | LEP, SAVINGS | `#22c55e` |
| CHECKING | CHECKING | `#0ea5e9` |
| CRYPTO | CRYPTO | `#f97316` |
| REAL_ESTATE | *(none yet)* | `#a855f7` |

The filter affects the summary card, chart, and account card grid simultaneously.

### History fetching

`useAllAccountsHistory` fetches `/accounts/{id}/history` for every account in parallel, merges all snapshots into a unified time series, and forward-fills missing values. It returns `{ balances, invested }` — two parallel arrays of `{ date, [accountId]: value }` points. Both are forward-filled independently.

It also injects each account's current balance at today's date if no snapshot exists for today, and carries the latest known `investedAmount` forward.

### Key files

- `frontend/src/pages/accounts/AccountsPage.tsx` — page with summary card, PnL chart, and grid
- `frontend/src/components/shared/AccountsStackedChart.tsx` — PnL line chart component
- `frontend/src/features/accounts/hooks.ts` — `useAllAccountsHistory` hook (returns `AccountsHistoryData`)
- `frontend/src/demo/index.ts` — mock history data (12 months per account)

### Flow

```
AccountsPage
  ├─ useAccounts()                    → list of all accounts
  ├─ useAllAccountsHistory()          → { balances, invested } merged time series
  │
  ├─ filteredAccounts (useMemo)       → accounts matching current filter
  ├─ hasHoldings                      → true if any filtered account is investment type
  │
  ├─ Summary card (totalBalance + PnL)
  │
  ├─ chartPnlData (useMemo)
  │   ├─ ALL  → aggregate (balance - invested) per type group per date
  │   └─ else → (balance - invested) per individual account per date
  │
  ├─ <AccountsStackedChart> (only if hasHoldings)
  │
  └─ Account card grid
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Virtual `Account` objects for type groups in ALL mode | `AccountsStackedChart` expects `Account[]` — avoids a second component or prop variant | Separate `GroupedChart` component |
| Client-side PnL computation in `useMemo` | PnL = balance - invested; both already available from the hook | Backend PnL endpoint |
| `LineChart` instead of stacked `AreaChart` | PnL can be negative — stacking breaks with negative values | Stacked areas with clamping |
| Separate `balances` / `invested` arrays from hook | Clean separation — chart consumes computed PnL, not raw data | Single array with `inv_` prefixed keys |
| Forward-fill missing balance and invested values | Accounts may not have snapshots for every date; chart needs continuous data | Interpolation (would invent non-real values) |
| Hide chart for cash-only filters | SAVINGS/CHECKING have PnL = 0 — a flat line chart adds no value | Show empty chart with flat zero lines |

## Gotchas / Pitfalls

- **`TYPE_TO_GROUP` must cover every `AccountType`** — if a new type is added to the enum but not to this map, those accounts silently disappear from the ALL chart.
- **`REAL_ESTATE` maps to no `AccountType`** — placeholder category. The filter pill shows but the grid/chart will be empty.
- **`Account.id` cast to `number`** — virtual group accounts use string keys (`'STOCKS'`, `'CRYPTO'`) cast as `number` via `as unknown as number`. This works because Recharts uses `dataKey` as a string lookup, but it's fragile.
- **`totalInvested` relies on the last invested point** — if an account has no snapshots at all, its invested amount is 0 and PnL equals its full balance. This is correct for newly created accounts where balance = invested.
- **Cash accounts have `investedAmount = balance`** — set by `AccountService.calculateInvestedAmount()` which returns `currentBalance` for accounts without holdings. This means their PnL = 0.
- **`useAllAccountsHistory` returns `AccountsHistoryData`** — not a flat array. Consumers must destructure `{ balances, invested }`.
- **Demo mock history** — `generateHistory()` in `frontend/src/demo/index.ts` creates 12 monthly points. The last point should match the account's `currentBalance` to stay consistent.

## Tests

No dedicated test files for this feature yet.

## Links

- i18n keys: `accounts.pnl`, `accounts.total`, `accounts.filters.*`, `dashboard.netWorthChange` in `en.json` / `fr.json`
- Related: [dashboard-time-range-isolation.md](./dashboard-time-range-isolation.md) — Dashboard PnL chart
- Related: [live-prices-holdings.md](./live-prices-holdings.md) — per-holding PnL calculation
- Related: [bank-logos.md](./bank-logos.md) — account card avatar (bank logo, falls back to `color`)
