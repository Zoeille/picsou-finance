# Feature: Bank Logos on Account Cards

> Last updated: 2026-07-01

## Context

Account cards on the Accounts page (`/accounts`) previously showed only a flat color swatch as the account's visual identity. Enable Banking's institution search already returns a real bank logo URL (`InstitutionData.logoUrl`) that was captured but never surfaced anywhere in the UI. This feature threads that logo through to the account and displays it as a circular avatar, falling back to the existing color when no logo is available.

## How it works

### Scope

Only accounts connected via **Enable Banking** get a real logo — it's the sole active `BankConnectorPort` implementation that returns one (see [bank-sync.md](./bank-sync.md)). Powens (disabled, experimental) hardcodes `logoUrl = null`. Manual accounts, crypto exchanges/wallets, Trade Republic, BoursoBank, Finary, real estate, and loans have no logo source and always show the color fallback. There is no manual logo picker — `color` remains the only user-editable visual field (`ColorPicker` / `AccountForm` are unchanged).

### Capture at connection time

1. `BankWizard` (`AddAccountModal.tsx`) shows each institution's `logoUrl` from `GET /sync/institutions` in the search list, purely for display — selecting a bank only sends `{ institutionId, institutionName }` to `POST /sync/initiate`. The client-supplied logo URL is never sent to the server or persisted; a client can't inject an arbitrary third-party image URL that every family member's browser would later fetch.
2. `SyncService.initiateConnection()` resolves the logo itself: it re-queries `bankConnector.searchInstitutions(institutionName, country)` (country parsed from `institutionId`, e.g. `"BankName::FR"` → `"FR"`), matches the result by exact institution id (falling back to a case-insensitive name match only if no id match exists), and stores that logo on the new `Requisition.logoUrl` column.
3. `SyncService.upsertAccount()` copies `requisition.getLogoUrl()` onto `Account.logoUrl` when creating a new account, and onto an existing account only if its `logoUrl` was still `null` (never overwrites a value once set).

### Backfill for pre-existing connections

Requisitions created before this feature shipped (or whose initial lookup missed) have `logoUrl = null`. `SyncService.ensureLogoUrl()` runs at the top of `resyncAll()` (daily scheduler), `retrySync()` (manual retry), and `resyncLatest()` (re-auth of an already-linked session): if the requisition has no logo yet, it re-searches institutions **scoped to the requisition's own country** (not the full multi-country catalog) and applies the same id-first/name-fallback matching described above.

The backfill is bounded to a single attempt per requisition via `Requisition.logoBackfillAttemptedAt`: the marker is set as soon as the search call completes (hit or miss), so a permanent miss (renamed institution, no provider logo) is never retried on subsequent syncs. A failed *network* call does not set the marker, so a transient provider outage can still be retried next sync. A failed or empty lookup is otherwise swallowed (logged as a warning) — the requisition simply stays logo-less.

### Rendering

`AccountCard.tsx`'s `AccountAvatar` and `AddAccountModal.tsx`'s `InstitutionLogo` are built on the shared `Avatar`/`AvatarImage`/`AvatarFallback` primitives (`frontend/src/components/ui/avatar.tsx`, Radix-based) rather than a hand-rolled `<img onError>` + `useState`. Radix re-attempts loading whenever `src` changes (e.g. a null logo becoming valid after backfill) and falls back to the color circle / `Landmark` icon automatically on load failure, with no risk of a stale `failed` flag latching across re-renders. The account detail page (`AccountDetailPage.tsx`) and the PnL chart legend (`AccountsStackedChart.tsx`) were intentionally left untouched — they use `account.color` as a small decorative dot/line color, not as the account's primary identity, and are out of scope for this change.

### Key files

- `backend/src/main/java/com/picsou/model/Account.java` — `logoUrl` column
- `backend/src/main/java/com/picsou/model/Requisition.java` — `logoUrl` + `logoBackfillAttemptedAt` columns
- `backend/src/main/java/com/picsou/service/SyncService.java` — `resolveLogoUrl()`, `ensureLogoUrl()`, `findInstitution()`, `upsertAccount()` copy logic
- `backend/src/main/resources/db/migration/V50__account_bank_logo.sql`
- `frontend/src/components/shared/AccountCard.tsx` — `AccountAvatar` sub-component
- `frontend/src/components/shared/AddAccountModal.tsx` — `InstitutionLogo` (bank search list preview)
- `frontend/src/components/ui/avatar.tsx` — shared Radix Avatar primitives

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Enable Banking only, no manual picker | It's the only connector with real logos; a curated logo library or free-form upload was out of scope for v1 | Static logo library / image upload per account |
| `color` kept as-is on every account | Still used by `AccountsStackedChart` line colors and the detail page dot; removing it would require a chart color strategy | Drop `color` once a logo exists |
| Best-effort backfill via re-search, not a migration | A migration can't make network calls safely; re-searching on the next scheduled/manual sync is free and self-healing | One-off backfill script at deploy time |
| Backfill only overwrites `logoUrl` when it was `null` | Never clobbers a logo the user already got from a real connection | Always refresh from the latest search result |

## Gotchas / Pitfalls

- **Powens never provides a logo.** `PowensBankConnector.searchInstitutions()` hardcodes `logoUrl = null` for every result. If Powens is ever re-enabled, its accounts will always show the color fallback until the adapter is updated.
- **Backfill match is best-effort, bounded to one attempt.** `ensureLogoUrl()` matches by institution id first, then falls back to a case-insensitive name match, scoped to the requisition's own country. A renamed institution on the provider side may never match — `logoBackfillAttemptedAt` prevents retrying forever, and the account just keeps showing its color, which degrades gracefully.
- **Rendering fallback is render-only.** A broken logo URL is not written back to the database — the same broken URL is retried on every mount (Radix re-attempts whenever `src` changes). This is intentional (the URL may become valid again, e.g. a CDN blip) but means a permanently-dead logo shows the color fallback every time rather than healing itself in storage.

## Tests

- `backend/src/test/java/com/picsou/service/SyncServiceTest.java` — logo resolved server-side at `initiateConnection()` by exact institution id; logo copied from `Requisition` to a new `Account`; backfill sets `Requisition.logoUrl` on resync scoped by country; backfill isn't retried once `logoBackfillAttemptedAt` is set; id match wins over a same-named institution from another country; a failed backfill lookup doesn't break the sync.
- `frontend/src/components/shared/AccountCard.test.tsx` — renders the logo image when the (stubbed) image load succeeds, the color fallback when absent, and falls back when the image load fails.

## Links

- Related: [bank-sync.md](./bank-sync.md) — Enable Banking connector and requisition lifecycle
- Related: [accounts-overview.md](./accounts-overview.md) — Accounts page and account card
