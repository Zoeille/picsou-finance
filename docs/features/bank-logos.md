# Feature: Logos on Account Cards

> Last updated: 2026-08-10

## Context

Account cards on the Accounts page (`/accounts`) previously showed only a flat color swatch as the account's visual identity. Enable Banking's institution search already returns a real bank logo URL (`InstitutionData.logoUrl`) that was captured but never surfaced anywhere in the UI. This feature threads that logo through to the account and displays it as a circular avatar, falling back to the existing color when no logo is available.

Connectors integrated by hand against a single provider have no catalog to ask for a logo, so a second, much smaller source was added: a map of brand assets that ship with the frontend, keyed by `Account.provider`. Meria, Amundi and Trade Republic are the entries so far.

On-chain wallets fit neither source. `WalletSyncService` writes the native ticker into `provider` (`BTC`, `SOL`, `ETH`...), so there is nothing stable to key a map on — and the asset a user wants isn't derivable anyway, since the chain can't tell whether the address lives on a Ledger. That case is served by a third source: a key stored on the account itself (`Account.logoKey`), seeded with the generic blockchain mark and swappable from the account form.

## How it works

### Scope

There are three logo sources, in priority order:

1. **A bundled asset the account points at** (`Account.logoKey`). Only **on-chain wallets** carry one — the API lets a client swap that key, never attach one to an account that has none, so a crypto *exchange* account can't be given a wallet mark either. It wins over everything else because it is the only source a user chose by hand.
2. **A provider-supplied URL on the account** (`Account.logoUrl`). Only **Enable Banking** fills it — it's the sole active `BankConnectorPort` implementation that returns one (see [bank-sync.md](./bank-sync.md)). Powens (disabled, experimental) hardcodes `logoUrl = null`.
3. **A bundled brand asset**, matched on `Account.provider` when neither of the above applies. Currently `MERIA`, Amundi and Trade Republic.

Everything else — manual accounts, Binance, Bourse Direct, BoursoBank, Finary, real estate, loans — still shows the color fallback. The only user-editable visual fields are `color` and, on a wallet, that logo key: there is no free-form logo upload and no picker on any other account type.

### Capture at connection time

1. `BankWizard` (`AddAccountModal.tsx`) shows each institution's `logoUrl` from `GET /sync/institutions` in the search list, purely for display — selecting a bank only sends `{ institutionId, institutionName }` to `POST /sync/initiate`. The client-supplied logo URL is never sent to the server or persisted; a client can't inject an arbitrary third-party image URL that every family member's browser would later fetch.
2. `SyncService.initiateConnection()` resolves the logo itself: it re-queries `bankConnector.searchInstitutions(institutionName, country)` (country parsed from `institutionId`, e.g. `"BankName::FR::personal"` → `"FR"`), matches the result by exact institution id, then on name+country alone, and only then by a case-insensitive name match, and stores that logo on the new `Requisition.logoUrl` column. The middle tier covers requisitions written before the id carried a PSU-type segment — see [bank-sync.md](./bank-sync.md).
3. `SyncService.upsertAccount()` copies `requisition.getLogoUrl()` onto `Account.logoUrl` when creating a new account, and onto an existing account only if its `logoUrl` was still `null` (never overwrites a value once set).

### Backfill for pre-existing connections

Requisitions created before this feature shipped (or whose initial lookup missed) have `logoUrl = null`. `SyncService.ensureLogoUrl()` runs at the top of `resyncAll()` (daily scheduler), `retrySync()` (manual retry), and `resyncLatest()` (re-auth of an already-linked session): if the requisition has no logo yet, it re-searches institutions **scoped to the requisition's own country** (not the full multi-country catalog) and applies the same three-tier matching described above.

The backfill is bounded to a single attempt per requisition via `Requisition.logoBackfillAttemptedAt`: the marker is set as soon as the search call completes (hit or miss), so a permanent miss (renamed institution, no provider logo) is never retried on subsequent syncs. A failed *network* call does not set the marker, so a transient provider outage can still be retried next sync. A failed or empty lookup is otherwise swallowed (logged as a warning) — the requisition simply stays logo-less.

### Bundled provider logos

`PROVIDER_LOGOS` (`frontend/src/lib/provider-logos.ts`) maps a `provider` string to an asset path, and `providerLogoUrl()` resolves it case-insensitively. For an account with no `logoKey` of its own, `AccountAvatar` falls back to `logoUrl ?? providerLogoUrl(provider)`, so a real connector logo always wins over a bundled one and nothing changes for accounts that already had one. The full order, key included, is in [Rendering](#rendering) below.

The key is the exact string the backend writes as `provider`. For crypto exchanges that is `ExchangeType.name()` (`CryptoExchangeSyncService.resolveAccount()`), hence `MERIA` rather than `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, i.e. `Amundi Épargne Salariale`, accent included; for Trade Republic it is the `"Trade Republic"` literal inlined in `TradeRepublicSyncService.upsertAccount()`. `provider-logos.test.ts` pins all three literals, so an accidental edit to the map — or an accent that drifts between Unicode normalizations on the frontend side — fails loudly instead of silently reverting to the color circle. It cannot see the backend, though: renaming what the connector writes is caught by nothing (see Gotchas).

Assets live under `frontend/public/` (`exchanges/` for crypto, `providers/` otherwise) rather than being imported from `src/assets/` like the app's own logo: a missing file then degrades to the account's color circle — exactly what a logo-less account already shows — instead of failing the build. The trade-off is that a typo'd or missing path is invisible at runtime, so `provider-logos.test.ts` expands `import.meta.glob('../../public/**/*.{svg,png}')` and asserts every mapped path exists on disk.

Nothing is written to the database and no backend change was needed: an existing Meria, Amundi or Trade Republic account picks the logo up on the next render, and demo mode gets it for free.

### The wallet logo key

`account.logo_key` (V75, `VARCHAR(32)`, nullable) holds the key of a bundled asset, resolved by `logoKeyUrl()` against `LOGO_KEYS` in the same frontend module. Two keys exist: `blockchain` (a generic on-chain wallet mark) and `ledger`.

- **Seeded, not derived.** `WalletSyncService.DEFAULT_LOGO_KEY` (`"blockchain"`) is written when a wallet's account is *created*, and never again — `resolveAccount()` only refreshes balance, `lastSyncedAt` and ticker on an account it finds, so a user's later choice survives every scheduled sync. V75 backfills the wallets that predate the column by joining `wallet_address` on the `wallet_<chain>_<id>` external id, guarded on `logo_key IS NULL` so a replayed migration can't reset a Ledger.
- **Changed from the account form.** `LogoPicker` (`frontend/src/components/shared/LogoPicker.tsx`) sits under `ColorPicker` in `AccountForm` and offers the keys in `WALLET_LOGO_CHOICES`. It renders only when the account being edited already has a key — which is exactly the on-chain wallets, since nothing else is created with one and the picker only ever swaps one key for another. That is also why no "no logo" option is offered: a wallet always shows one of the two, and there is nothing to undo.
- **Opaque to the backend.** `AccountRequest.logoKey` is validated as a lowercase slug (`^[a-z0-9-]{1,32}$`), not against a list of known keys — the assets live in the frontend, so a fixed list here would mean a backend release for every new one. An unknown key resolves to `null` client-side and the account falls through to its provider logo, then its color.
- **Kept when absent.** `AccountService.update()` writes `req.logoKey()` only when it is non-null, the way it treats `color` rather than `ticker`. A client that doesn't know about logos — the `update_account` MCP tool, an older frontend — would otherwise clear a wallet's Ledger mark as a side effect of renaming the account.
- **Only swappable, never attachable.** `AccountService.normalizeLogoKey()` keeps a key only on a `CRYPTO` account that already stores one, which is the same test the picker uses — the stored key answers "is this a wallet?" without an `isWallet` flag having to reach the backend. So `create()` always drops a key a request carries (only `WalletSyncService` seeds one, and it builds the row itself), and `update()` ignores one sent for an account that has none. `CRYPTO` alone would not be enough: it also covers exchange accounts, and a hand-crafted `PUT` could then bury Meria's brand mark under a Ledger.
- **Dropped when the type changes.** The same normalization clears the key when an account is retyped away from `CRYPTO`, and `AccountForm` hides the picker the moment the type changes. Retyping a wallet to `CHECKING` would otherwise leave a blockchain mark on it permanently: the picker has no "none" option and an omitted key is kept. This is also the only way to clear a key from the UI.

### Rendering

`AccountAvatar` resolves `logoKeyUrl(logoKey) ?? logoUrl ?? providerLogoUrl(provider)` — the three sources of the Scope section, in that order. `AccountCard.tsx`'s `AccountAvatar` and `AddAccountModal.tsx`'s `InstitutionLogo` are built on the shared `Avatar`/`AvatarImage`/`AvatarFallback` primitives (`frontend/src/components/ui/avatar.tsx`, Radix-based) rather than a hand-rolled `<img onError>` + `useState`. Radix re-attempts loading whenever `src` changes (e.g. a null logo becoming valid after backfill) and falls back to the color circle / `Landmark` icon automatically on load failure, with no risk of a stale `failed` flag latching across re-renders. The account detail page (`AccountDetailPage.tsx`) and the PnL chart legend (`AccountsStackedChart.tsx`) were intentionally left untouched — they use `account.color` as a small decorative dot/line color, not as the account's primary identity, and are out of scope for this change.

### Key files

- `backend/src/main/java/com/picsou/model/Account.java` — `logoUrl` column
- `backend/src/main/java/com/picsou/model/Requisition.java` — `logoUrl` + `logoBackfillAttemptedAt` columns
- `backend/src/main/java/com/picsou/service/SyncService.java` — `resolveLogoUrl()`, `ensureLogoUrl()`, `findInstitution()`, `upsertAccount()` copy logic
- `backend/src/main/resources/db/migration/V50__account_bank_logo.sql`
- `frontend/src/components/shared/AccountCard.tsx` — `AccountAvatar` sub-component
- `frontend/src/components/shared/AddAccountModal.tsx` — `InstitutionLogo` (bank search list preview)
- `frontend/src/components/ui/avatar.tsx` — shared Radix Avatar primitives
- `backend/src/main/resources/db/migration/V75__account_logo_key.sql` — `account.logo_key` + wallet backfill
- `backend/src/main/java/com/picsou/service/WalletSyncService.java` — `DEFAULT_LOGO_KEY`, written at account creation
- `backend/src/main/java/com/picsou/service/AccountService.java` — `normalizeLogoKey()`: a key is swappable only where one is already stored, and `update()` keeps it when the request omits it
- `frontend/src/lib/provider-logos.ts` — `PROVIDER_LOGOS` + `LOGO_KEYS` maps, `providerLogoUrl()` / `logoKeyUrl()`
- `frontend/src/components/shared/LogoPicker.tsx` — the wallet's blockchain/Ledger choice
- `frontend/src/components/shared/AccountForm.tsx` — renders the picker for accounts that carry a key
- `frontend/public/exchanges/meria.svg` — Meria's mark
- `frontend/public/providers/amundi.png` — Amundi's mark
- `frontend/public/providers/trade-republic.svg` — Trade Republic's mark
- `frontend/public/wallets/blockchain.svg`, `frontend/public/wallets/ledger.svg` — the two wallet marks

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Provider logos from Enable Banking only, never picked by hand | It's the only connector with real logos; a curated logo library or free-form upload was out of scope for v1. The wallet picker one row down is the sole exception, and it chooses between two bundled marks rather than supplying a logo | Static logo library / image upload per account |
| Bundled asset + frontend map | No institution catalog to query for either provider, and hotlinking is a dead end: `meria.com/favicon.ico` and `/apple-touch-icon.png` both answer **403** to non-browser clients behind their WAF (the only asset that does serve, `og:image`, is a 3299×2475 dark share banner), and a logo pulled off Amundi's own site would rot the moment they reorganise it. A file in `public/` is the only source that can't break underneath us | Hardcoded remote URL on the account; a generic backend favicon fetcher/cache serving `/api/logos/{provider}` (would also cover BoursoBank, IBKR — but the same WAF breaks it on Meria) |
| Mapping in the frontend, not written to `Account.logoUrl` | No migration and no database backfill is needed for a bundled logo — an existing account simply renders it on the next load (unlike an Enable Banking `logoUrl`, which does need the `ensureLogoUrl()` backfill two rows down). It works in demo mode, and the asset path stays a frontend concern instead of the backend storing a frontend URL | `CryptoExchangePort.logoUrl()` / `AmundiPort` returning a logo copied onto the account by the sync service, mirroring Enable Banking |
| A key stored on the account, not a logo derived from `provider` | A wallet's `provider` is its native ticker, so a provider map can't reach it — and no connector can know whether an address is a Ledger. Storing the choice is also what makes it follow the user across devices and family members, like `color` | Extending `PROVIDER_LOGOS` with every ticker (wrong mark for a Ledger, and unbounded); remembering the choice in `localStorage` (per-browser, invisible to the rest of the family) |
| An opaque slug column, validated by regex | Mirrors how `color` is stored: the backend persists a value it doesn't interpret, the frontend owns what it means. Adding a wallet asset stays a frontend-only change | A Postgres enum or a backend whitelist of keys (a migration + release per asset); writing the asset path itself into `logoUrl` (the backend would be storing a frontend URL, and a member-editable one at that — exactly the injection the capture step avoids) |
| A key swappable only where one already exists, in the picker *and* in `AccountService` | An on-chain wallet is the only account with a genuine choice to make; every other account's logo is determined by its connector or its provider name. Gating on the stored key means no new field has to be plumbed through just to answer "is this a wallet?" — and applying the same test server-side keeps that contract true for a client that isn't the picker | A picker on every `CRYPTO` account, or a backend that accepts a key on any of them (would let an exchange account override its own brand mark); an `isWallet` flag added to `AccountResponse` |
| `color` kept as-is on every account | Still used by `AccountsStackedChart` line colors and the detail page dot; removing it would require a chart color strategy | Drop `color` once a logo exists |
| Best-effort backfill via re-search, not a migration | A migration can't make network calls safely; re-searching on the next scheduled/manual sync is free and self-healing | One-off backfill script at deploy time |
| Backfill only overwrites `logoUrl` when it was `null` | Never clobbers a logo the user already got from a real connection | Always refresh from the latest search result |

## Gotchas / Pitfalls

- **Powens never provides a logo.** `PowensBankConnector.searchInstitutions()` hardcodes `logoUrl = null` for every result. If Powens is ever re-enabled, its accounts will always show the color fallback until the adapter is updated.
- **Backfill match is best-effort, bounded to one attempt.** `ensureLogoUrl()` matches by institution id first, then falls back to a case-insensitive name match, scoped to the requisition's own country. A renamed institution on the provider side may never match — `logoBackfillAttemptedAt` prevents retrying forever, and the account just keeps showing its color, which degrades gracefully.
- **A bundled logo is keyed on a free-text column.** `Account.provider` is written by each connector with no shared enum, so renaming what `CryptoExchangeSyncService` stores (today `ExchangeType.name()`), what `AmundiSyncService` stores, or the `"Trade Republic"` literal in `TradeRepublicSyncService` silently drops the logo back to the color circle. The lookup is case-insensitive, which absorbs a `Meria`/`MERIA` change but nothing more — and Amundi's key carries an accent, so it is also sensitive to Unicode normalization.
- **A bundled mark needs its own margin.** The avatar is a circle, so the square the image is fitted into is inscribed in it: a mark drawn edge to edge in its `viewBox` gets its corners clipped, and a wide one loses its ends. Brand SVGs ship cropped tight to the artwork, so `trade-republic.svg` and `blockchain.svg` carry a deliberately padded `viewBox`. Padding the avatar instead was rejected — it would shrink every Enable Banking logo, and those already come with their own whitespace.
- **A mapped asset that isn't there fails silently.** `public/` files aren't resolved at build time, so a typo in `PROVIDER_LOGOS` just yields a 404 and the usual color fallback. `provider-logos.test.ts` is the only thing that catches it — keep it in step when adding an entry.
- **Changing the account type clears the logo, for good.** A key survives only on a `CRYPTO` account that already stores one (`AccountService.normalizeLogoKey()`). Retyping a wallet and retyping it back does not restore the mark: the account now has no stored key, so neither the picker nor a hand-written `PUT` can put one back, and the next sync won't re-seed it either since the connector only writes the key at account creation.
- **A wallet's key is written once.** `WalletSyncService` seeds `logoKey` at account *creation* only. A wallet whose account row somehow predates V75 and escaped its backfill (a hand-restored dump, an account re-keyed by hand) never grows one on a later sync — it falls through to the color circle, and the picker won't appear for it either, since the picker is gated on the key already being there.
- **An unknown key is silent.** The backend accepts any lowercase slug, so an account can carry a key this build has no asset for (a downgrade, a hand-edited row). `logoKeyUrl()` returns `null` and the card falls through to the provider logo, then the color — no error, no broken image.
- **`update_account` over MCP doesn't send a logo key.** `AccountService.update()` keeps the stored one when the request omits it, which is what makes that safe. Note the same tool *does* clear `provider` (it hardcodes `null`), so it is not a general-purpose account editor.
- **Rendering fallback is render-only.** A broken logo URL is not written back to the database — the same broken URL is retried on every mount (Radix re-attempts whenever `src` changes). This is intentional (the URL may become valid again, e.g. a CDN blip) but means a permanently-dead logo shows the color fallback every time rather than healing itself in storage.

## Tests

- `backend/src/test/java/com/picsou/service/SyncServiceTest.java` — logo resolved server-side at `initiateConnection()` by exact institution id; logo copied from `Requisition` to a new `Account`; backfill sets `Requisition.logoUrl` on resync scoped by country; backfill isn't retried once `logoBackfillAttemptedAt` is set; id match wins over a same-named institution from another country; a failed backfill lookup doesn't break the sync.
- `frontend/src/components/shared/AccountCard.test.tsx` — renders the logo image when the (stubbed) image load succeeds, the color fallback when absent, falls back when the image load fails, renders the bundled asset for a provider with no `logoUrl`, and prefers a connector-supplied `logoUrl` over a bundled one. Its `MockImage` must invoke `load`/`error` listeners with an `{ currentTarget }` event — Radix's `handleLoad` reads it, and calling them bare throws inside Radix instead of failing the assertion.
- `frontend/src/lib/provider-logos.test.ts` — every path in `PROVIDER_LOGOS` and `LOGO_KEYS` is root-relative and exists under `frontend/public/`, the exact provider literal each connector writes (Meria, Amundi, Trade Republic), every key the picker offers is mapped, plus the case-insensitive provider lookup and the `null`/unknown-key cases.
- `backend/src/test/java/com/picsou/migration/AccountLogoKeyMigrationTest.java` — V75 against real PostgreSQL: existing wallets are backfilled with `blockchain` (including the lowercased chain segment), accounts that merely look like wallets and exchange accounts stay `NULL`, the backfilled value equals `WalletSyncService.DEFAULT_LOGO_KEY`, and a replay of the file leaves a user's `ledger` choice alone.
- `backend/src/test/java/com/picsou/service/WalletSyncServiceTest.java` — a new wallet account is created with the default key; an existing one keeps the key the user picked across a resync.
- `backend/src/test/java/com/picsou/service/AccountServiceTest.java` — `update()` stores the key the picker sent, keeps the stored one when the request omits it, drops it when the account is retyped away from `CRYPTO`, and ignores one sent for a crypto account carrying none (an exchange); `create()` ignores a key whatever the type.

## Links

- Related: [bank-sync.md](./bank-sync.md) — Enable Banking connector and requisition lifecycle
- Related: [accounts-overview.md](./accounts-overview.md) — Accounts page and account card
