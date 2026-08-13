# Feature: Accounts Overview (PnL chart + summary card + asset type filters)

> Last updated: 2026-08-13

## Context

The Accounts page (`/accounts`) shows a grid of account cards. Users need a visual overview of PnL evolution over time, with the ability to filter by asset type (stocks, savings, crypto, etc.). A summary card at top shows the total balance and PnL for the current filter, similar to the Dashboard hero card.

## How it works

### Account card anatomy

`AccountCard` renders every account to the same five-line shape, so the grid stays on one
rhythm whatever the asset:

| Line | Bank / broker / crypto account | Property (`REAL_ESTATE`) |
|---|---|---|
| Mark | wallet logo key, connector logo, bundled brand asset, or color circle — see [bank-logos.md](./bank-logos.md) | the property-kind glyph on a tint of the account color |
| Name | `name` + type badge + co-ownership share | same |
| Subtitle | `provider` | property kind and city, joined by ` · ` |
| Balance | `currentBalanceEur` (negated and red for a `LOAN`) | same |
| Freshness | `lastSyncedAt`, graded green → yellow → orange → red by age | `realEstate.lastValuedAt`, graded on the monthly scale |

A property is a manual account: it has no `provider` and nothing ever writes its
`lastSyncedAt`, so before this shape it rendered two lines shorter than its neighbours *and*
carried an unrealized-gain line no other type showed. The gain now lives only on the account
detail page and in `RealEstateSummaryCard`; the kind, the city and the valuation date fill
the two slots that were empty.

Every line is still conditional — an account with no provider and no sync date (a manual
`OTHER` account, or a property described but never valued) renders short, as it always has.
Only the property case, where the data existed but was not surfaced, was fixed.

The freshness line is colour-graded by age, so how old a figure is reads at a glance instead
of only crossing one 48h threshold. `freshnessLevel(date, bounds, now)`
(`frontend/src/lib/utils.ts`) buckets the age against a bounds object; `FRESHNESS_TEXT_CLASS`
maps the bucket to a Tailwind pair that stays legible in both themes.

**Two scales, because the two dates are produced on entirely different cadences** — a scale
that cries wolf is one users learn to ignore:

| Level | `lastSyncedAt` (daily jobs) | `lastValuedAt` (monthly job) | Colour |
|---|---|---|---|
| `fresh` | < 24 h | < 35 d | green |
| `recent` | < 48 h | < 60 d | yellow |
| `stale` | < 7 d | < 90 d | orange |
| `old` | beyond | beyond | red |

Both live in `frontend/src/lib/constants.ts` as `SYNC_FRESHNESS_BOUNDS_MS` and
`VALUATION_FRESHNESS_BOUNDS_MS`. The valuation scale is month-shaped because
`SchedulerService.monthlyPropertyValuation` runs on the 1st of each month against sources that
themselves refresh twice a year: a 40-day-old estimate is the system working as designed, and
would be permanently red on the sync scale.

The `TriangleAlert` glyph and its tooltip are **narrower than the colour**: they appear only on
the `stale`/`old` levels *and* only for non-manual accounts. Colour alone carries no meaning for
a colour-blind reader, so the worst levels need a second signal — but the tooltip names a dead
provider session and tells the user to reconnect, which is meaningless for a figure they type in
themselves. A manual account still gets the colour, since age is age.

A missing date is `unknown`, not `old`: never synced is not the same as very stale, and must not
read as an alarm. In this card the line is simply not rendered in that case.

The card re-renders on a 5-minute interval so a tab left open crosses a boundary without a
remount, and `now` is a parameter of `freshnessLevel` rather than a `Date.now()` call inside it,
which keeps the helper pure and its tests free of fake timers.

The glyphs come from `frontend/src/lib/property-icons.ts` (`PROPERTY_KIND_ICONS`), which
`AddPropertyModal`'s kind picker shares so a property looks the same wherever it is shown.
They are keyed on `RealEstateMetadata.propertyKind` — the backend's `PropertyKind.parse` of
the free-text `property_type` column — never on the raw string.

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
| SAVINGS | LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL, SAVINGS | `#22c55e` |
| CHECKING | CHECKING | `#0ea5e9` |
| CRYPTO | CRYPTO | `#f97316` |
| REAL_ESTATE | REAL_ESTATE | `#a855f7` |

The filter affects the summary card, chart, and account card grid simultaneously.

### History fetching

`useAllAccountsHistory` fetches `/accounts/{id}/history` for every account in parallel, merges all snapshots into a unified time series, and forward-fills missing values. It returns `{ balances, invested }` — two parallel arrays of `{ date, [accountId]: value }` points. Both are forward-filled independently.

It also injects each account's current balance at today's date if no snapshot exists for today, and carries the latest known `investedAmount` forward.

### Key files

- `frontend/src/pages/accounts/AccountsPage.tsx` — page with summary card, PnL chart, and grid
- `frontend/src/components/shared/AccountCard.tsx` — one card; `AccountAvatar` / `PropertyAvatar`
- `frontend/src/lib/property-icons.ts` — `PROPERTY_KIND_ICONS`, shared with `AddPropertyModal`
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

- **A new `PropertyKind` needs an entry in `PROPERTY_KIND_ICONS`.** The map is typed
  `Record<PropertyKind, LucideIcon>`, so a missing key fails the build rather than silently
  dropping the glyph — but the enum lives in the backend, and adding a value there without
  adding it to `PropertyKind.parse` leaves `propertyKind` null and the card back on the color
  circle.
- **The property glyph tints itself with relative color syntax.** `PropertyAvatar` sets
  `--account-color` inline and derives both the disc and the glyph from it, darkening the
  glyph in light mode and brightening it in dark (see the
  [CSS relative color ADR](../decisions/2026-04-08-css-relative-color-syntax.md)). The
  account palette runs from indigo to yellow; a raw yellow-500 glyph on its own pale tint is
  barely visible in light mode.
- **Never derive an account type's label key from its name.** `accountTypeLabelKey()` in `lib/constants.ts` is the only mapping; a local `type.toLowerCase()` renders the raw key (`accountTypes.livret_a`) for any type whose key isn't simply its lowercased value. See [add-account-modal.md](./add-account-modal.md#account-type-labels).
- **`TYPE_TO_GROUP` must cover every `AccountType`** — if a new type is added to the enum but not to this map, those accounts silently disappear from the ALL chart.
- **`currentBalanceEur` is the account's full value, not the viewer's share.** Co-owned accounts carry `sharePercent` alongside it (see [account-ownership-shares.md](account-ownership-shares.md)); anything summing balances on this page must apply it, because the server only weights its own aggregates.
- **`Account.id` cast to `number`** — virtual group accounts use string keys (`'STOCKS'`, `'CRYPTO'`) cast as `number` via `as unknown as number`. This works because Recharts uses `dataKey` as a string lookup, but it's fragile.
- **`totalInvested` relies on the last invested point** — if an account has no snapshots at all, its invested amount is 0 and PnL equals its full balance. This is correct for newly created accounts where balance = invested.
- **Cash accounts have `investedAmount = balance`** — set by `AccountService.calculateInvestedAmount()` which returns `currentBalance` for accounts without holdings. This means their PnL = 0.
- **`useAllAccountsHistory` returns `AccountsHistoryData`** — not a flat array. Consumers must destructure `{ balances, invested }`.
- **Demo mock history** — `generateHistory()` in `frontend/src/demo/index.ts` creates 12 monthly points. The last point should match the account's `currentBalance` to stay consistent.

## Tests

- `frontend/src/components/shared/AccountCard.test.tsx` — a property renders its kind glyph
  instead of the color circle, each kind gets its own glyph, an unparseable `property_type`
  falls back to the circle, kind and city stand in for the provider line (and either half
  may be missing), the valuation date replaces the sync date, and the unrealized gain is
  gone. Glyph assertions match lucide's own `lucide-<icon>` class. A second block covers the
  stale badge: flagged past 48h, the plain last-sync line below it, never on a manual account.
- Nothing covers the PnL chart, the summary card or the filters yet.

## Links

- i18n keys: `accounts.pnl`, `accounts.total`, `accounts.filters.*`, `accounts.lastSync`,
  `accounts.lastValuation`, `property.kind.*`, `dashboard.netWorthChange`
- Related: [dashboard-time-range-isolation.md](./dashboard-time-range-isolation.md) — Dashboard PnL chart
- Related: [live-prices-holdings.md](./live-prices-holdings.md) — per-holding PnL calculation
- Related: [bank-logos.md](./bank-logos.md) — account card avatar (bank logo, falls back to `color`)
- Related: [real-estate-valuation.md](./real-estate-valuation.md) — where `propertyKind` and `lastValuedAt` come from
