# Feature: Logos on Account Cards

> Last updated: 2026-08-10

## Context

Account cards on the Accounts page (`/accounts`) previously showed only a flat color swatch as the account's visual identity. Enable Banking's institution search already returns a real bank logo URL (`InstitutionData.logoUrl`) that was captured but never surfaced anywhere in the UI. This feature threads that logo through to the account and displays it as a circular avatar, falling back to the existing color when no logo is available.

Connectors integrated by hand against a single provider have no catalog to ask for a logo, so a second, much smaller source was added: a map of brand assets that ship with the frontend, keyed by `Account.provider`. Meria and Amundi are the entries so far.

## How it works

### Scope

There are two logo sources, in priority order:

1. **A provider-supplied URL on the account** (`Account.logoUrl`). Only **Enable Banking** fills it — it's the sole active `BankConnectorPort` implementation that returns one (see [bank-sync.md](./bank-sync.md)). Powens (disabled, experimental) hardcodes `logoUrl = null`.
2. **A bundled brand asset**, matched on `Account.provider` when the account has no `logoUrl`. Currently `MERIA` and Amundi.

Everything else — manual accounts, on-chain wallets, Binance, Trade Republic, Bourse Direct, BoursoBank, Finary, real estate, loans — still shows the color fallback. There is no manual logo picker — `color` remains the only user-editable visual field (`ColorPicker` / `AccountForm` are unchanged).

### Capture at connection time

1. `BankWizard` (`AddAccountModal.tsx`) shows each institution's `logoUrl` from `GET /sync/institutions` in the search list, purely for display — selecting a bank only sends `{ institutionId, institutionName }` to `POST /sync/initiate`. The client-supplied logo URL is never sent to the server or persisted; a client can't inject an arbitrary third-party image URL that every family member's browser would later fetch.
2. `SyncService.initiateConnection()` resolves the logo itself: it re-queries `bankConnector.searchInstitutions(institutionName, country)` (country parsed from `institutionId`, e.g. `"BankName::FR::personal"` → `"FR"`), matches the result by exact institution id, then on name+country alone, and only then by a case-insensitive name match, and stores that logo on the new `Requisition.logoUrl` column. The middle tier covers requisitions written before the id carried a PSU-type segment — see [bank-sync.md](./bank-sync.md).
3. `SyncService.upsertAccount()` copies `requisition.getLogoUrl()` onto `Account.logoUrl` when creating a new account, and onto an existing account only if its `logoUrl` was still `null` (never overwrites a value once set).

### Backfill for pre-existing connections

Requisitions created before this feature shipped (or whose initial lookup missed) have `logoUrl = null`. `SyncService.ensureLogoUrl()` runs at the top of `resyncAll()` (daily scheduler), `retrySync()` (manual retry), and `resyncLatest()` (re-auth of an already-linked session): if the requisition has no logo yet, it re-searches institutions **scoped to the requisition's own country** (not the full multi-country catalog) and applies the same three-tier matching described above.

The backfill is bounded to a single attempt per requisition via `Requisition.logoBackfillAttemptedAt`: the marker is set as soon as the search call completes (hit or miss), so a permanent miss (renamed institution, no provider logo) is never retried on subsequent syncs. A failed *network* call does not set the marker, so a transient provider outage can still be retried next sync. A failed or empty lookup is otherwise swallowed (logged as a warning) — the requisition simply stays logo-less.

### Bundled provider logos

`PROVIDER_LOGOS` (`frontend/src/lib/provider-logos.ts`) maps a `provider` string to an asset path, and `providerLogoUrl()` resolves it case-insensitively. `AccountAvatar` uses `logoUrl ?? providerLogoUrl(provider)`, so a real connector logo always wins over a bundled one and nothing changes for accounts that already had one.

The key is the exact string the backend writes as `provider`. For crypto exchanges that is `ExchangeType.name()` (`CryptoExchangeSyncService.resolveAccount()`), hence `MERIA` rather than `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, i.e. `Amundi Épargne Salariale`, accent included. `provider-logos.test.ts` pins both literals, so an accidental edit to the map — or an accent that drifts between Unicode normalisations on the frontend side — fails loudly instead of silently reverting to the color circle. It cannot see the backend, though: renaming what the connector writes is caught by nothing (see Gotchas).

Assets live under `frontend/public/` (`exchanges/` for crypto, `providers/` otherwise) rather than being imported from `src/assets/` like the app's own logo: a missing file then degrades to the account's color circle — exactly what a logo-less account already shows — instead of failing the build. The trade-off is that a typo'd or missing path is invisible at runtime, so `provider-logos.test.ts` expands `import.meta.glob('../../public/**/*.{svg,png}')` and asserts every mapped path exists on disk.

Nothing is written to the database and no backend change was needed: an existing Meria or Amundi account picks the logo up on the next render, and demo mode gets it for free.

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
- `frontend/src/lib/provider-logos.ts` — `PROVIDER_LOGOS` map + `providerLogoUrl()`
- `frontend/public/exchanges/meria.svg` — Meria's mark
- `frontend/public/providers/amundi.png` — Amundi's mark

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Enable Banking only, no manual picker | It's the only connector with real logos; a curated logo library or free-form upload was out of scope for v1 | Static logo library / image upload per account |
| Bundled asset + frontend map | No institution catalog to query for either provider, and hotlinking is a dead end: `meria.com/favicon.ico` and `/apple-touch-icon.png` both answer **403** to non-browser clients behind their WAF (the only asset that does serve, `og:image`, is a 3299×2475 dark share banner), and a logo pulled off Amundi's own site would rot the moment they reorganise it. A file in `public/` is the only source that can't break underneath us | Hardcoded remote URL on the account; a generic backend favicon fetcher/cache serving `/api/logos/{provider}` (would also cover Trade Republic, BoursoBank, IBKR — but the same WAF breaks it on Meria) |
| Mapping in the frontend, not written to `Account.logoUrl` | No migration and no database backfill is needed for a bundled logo — an existing account simply renders it on the next load (unlike an Enable Banking `logoUrl`, which does need the `ensureLogoUrl()` backfill two rows down). It works in demo mode, and the asset path stays a frontend concern instead of the backend storing a frontend URL | `CryptoExchangePort.logoUrl()` / `AmundiPort` returning a logo copied onto the account by the sync service, mirroring Enable Banking |
| `color` kept as-is on every account | Still used by `AccountsStackedChart` line colors and the detail page dot; removing it would require a chart color strategy | Drop `color` once a logo exists |
| Best-effort backfill via re-search, not a migration | A migration can't make network calls safely; re-searching on the next scheduled/manual sync is free and self-healing | One-off backfill script at deploy time |
| Backfill only overwrites `logoUrl` when it was `null` | Never clobbers a logo the user already got from a real connection | Always refresh from the latest search result |

## Gotchas / Pitfalls

- **Powens never provides a logo.** `PowensBankConnector.searchInstitutions()` hardcodes `logoUrl = null` for every result. If Powens is ever re-enabled, its accounts will always show the color fallback until the adapter is updated.
- **Backfill match is best-effort, bounded to one attempt.** `ensureLogoUrl()` matches by institution id first, then falls back to a case-insensitive name match, scoped to the requisition's own country. A renamed institution on the provider side may never match — `logoBackfillAttemptedAt` prevents retrying forever, and the account just keeps showing its color, which degrades gracefully.
- **A bundled logo is keyed on a free-text column.** `Account.provider` is written by each connector with no shared enum, so renaming what `CryptoExchangeSyncService` stores (today `ExchangeType.name()`) or what `AmundiSyncService` stores silently drops the logo back to the color circle. The lookup is case-insensitive, which absorbs a `Meria`/`MERIA` change but nothing more — and Amundi's key carries an accent, so it is also sensitive to Unicode normalisation.
- **A mapped asset that isn't there fails silently.** `public/` files aren't resolved at build time, so a typo in `PROVIDER_LOGOS` just yields a 404 and the usual color fallback. `provider-logos.test.ts` is the only thing that catches it — keep it in step when adding an entry.
- **Rendering fallback is render-only.** A broken logo URL is not written back to the database — the same broken URL is retried on every mount (Radix re-attempts whenever `src` changes). This is intentional (the URL may become valid again, e.g. a CDN blip) but means a permanently-dead logo shows the color fallback every time rather than healing itself in storage.

## Tests

- `backend/src/test/java/com/picsou/service/SyncServiceTest.java` — logo resolved server-side at `initiateConnection()` by exact institution id; logo copied from `Requisition` to a new `Account`; backfill sets `Requisition.logoUrl` on resync scoped by country; backfill isn't retried once `logoBackfillAttemptedAt` is set; id match wins over a same-named institution from another country; a failed backfill lookup doesn't break the sync.
- `frontend/src/components/shared/AccountCard.test.tsx` — renders the logo image when the (stubbed) image load succeeds, the color fallback when absent, falls back when the image load fails, renders the bundled asset for a provider with no `logoUrl`, and prefers a connector-supplied `logoUrl` over a bundled one. Its `MockImage` must invoke `load`/`error` listeners with an `{ currentTarget }` event — Radix's `handleLoad` reads it, and calling them bare throws inside Radix instead of failing the assertion.
- `frontend/src/lib/provider-logos.test.ts` — every path in `PROVIDER_LOGOS` is root-relative and exists under `frontend/public/`, plus the case-insensitive lookup and the `null` cases.

## Links

- Related: [bank-sync.md](./bank-sync.md) — Enable Banking connector and requisition lifecycle
- Related: [accounts-overview.md](./accounts-overview.md) — Accounts page and account card
