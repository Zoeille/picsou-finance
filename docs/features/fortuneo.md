# Feature: Fortuneo sync

> Status: 🚧 **Login, 2FA, account listing, and transactions are verified
> end-to-end against a live account through the full app stack (frontend →
> backend → sidecar). Per-position holdings are implemented and validated
> against real captured data, but the fetch path has taken fifteen
> live-driven iterations and is still not confirmed live end-to-end.**
> Root cause of that whole saga: **Fortuneo runs two frontends** — a
> modern SPA (`/mon-espace`, OAuth/PKCE) and a legacy JSP site
> (`/fr/prive/…`, session-cookie auth) users can opt into — and positions
> exist only on the legacy one. Every earlier attempt requested the
> positions URL while the browser sat on the SPA, so the SPA answered with
> its own shell or login form. Compounding the confusion,
> `storage_state()` restores the legacy site's cookies but not the SPA's
> OAuth token, so the SPA page is genuinely logged out even while the
> api.fortuneo.fr calls keep succeeding on their separate `apikey`. The
> connector now performs the site's own `POST /ssoacces` handshake to mint
> a legacy session, lands on the legacy home page (`default.jsp?ANav=1`),
> and reads the account's situation page from an iframe there — the one
> request shape the HAR confirms returns holdings. See "Verification
> boundaries" bugs 7–29 for the full blow-by-blow; every fix was driven by
> a real live-test failure, not speculation. Positions were
> reverse-engineered from a
> full-site HAR capture that happened to include the PEA's real holdings;
> the parser (`parse_portfolio_positions`) was validated against that real
> 14-position HTML page with every row's price×quantity and cost-basis
> arithmetic cross-checked to the cent (see "Verification boundaries"). A
> PEA's `cashBalance` is derived as `balanceEur - sum(positions)` rather
> than guessed, since a PEA has no separate cash-pocket account to source
> it from (unlike CTO). See "Discovery status" for the full mapping.

## Scope

The connector imports Fortuneo accounts as Picsou `PEA`, `COMPTE_TITRES`,
`CHECKING` (Compte Courant), and `SAVINGS` (Livret) accounts. A successful
snapshot contains:

- the authoritative account valuation and cash balance in EUR;
- for `PEA`/`COMPTE_TITRES`: every open position, its quantity and EUR
  average buying price when safely available, the broker quote in its native
  currency, and the broker-provided position valuation and unrealized P&L in
  EUR;
- for `CHECKING`/`SAVINGS`: no positions (`cashBalance == balanceEur`);
- recent transactions for every account type, replacing the trailing 90-day
  window on each sync (see "Cash accounts and transactions" below).

This is an unofficial, read-only integration. Fortuneo can change its login
page or account/position/transaction pages without notice. The connector is
therefore designed to fail closed: an incomplete or inconsistent response
never replaces the last valid portfolio.

## Discovery status

Unlike Bourse Direct and BoursoBank, no public reference implementation of
Fortuneo's login flow exists (the only public integration found, a Cozy
Cloud community konnector, explicitly does not support per-login 2FA and
does not document its scraping approach). Two real HAR captures of
authenticated sessions — the first starting mid-login (after credential
submission), the second starting from a fresh page load but through a
login flow that opens in a new tab/popup (so DevTools had to be attached to
that popup to record it) — resolved most of the data-access questions:

**Confirmed:**

- Fortuneo's SPA authenticates via an OAuth2 PKCE-style flow
  (`POST https://api.fortuneo.fr/oauth-pkce/authorization-code` with
  `access_code` + `password` + a device `fingerprint`/`fingerprintDetails`
  pair, then `POST .../oauth-pkce/token`), followed by a legacy session
  bridge (`POST https://mabanque.fortuneo.fr/ssoacces`). Since a real
  Chromium browser computes the fingerprint itself, Playwright only needs to
  fill and submit the visible login form — it does not need to replicate
  this exchange. The resulting session is cookie-based (`context.storage_state()`
  captures it across both `mabanque.fortuneo.fr` and `api.fortuneo.fr`,
  exactly like the Bourse Direct sidecar). **Because this form is rendered
  entirely client-side by the SPA's JS, its field selectors are invisible to
  network capture** — a HAR can never reveal DOM structure, only traffic.
- **The primary, modern account-listing + balance source is a single GraphQL
  query**: `POST https://api.fortuneo.fr/account-items-bff/graphql` (an
  "Equipment" query) returns every account across every product category —
  life insurance, PER, PEA/PEA-PME, CTO, the CTO's linked cash pocket,
  Compte Courant, Livret, mortgages, external accounts — with id, label,
  masked account number, `type.value`, and (via GraphQL `@defer`) balance.
  The HTTP response is chunked `multipart/mixed; boundary="-"`: a base part
  plus one part per deferred fragment, each merged in by a JSON-pointer-like
  `path`. `fortuneo_parser.parse_equipment_multipart` parses and fully
  merges this; `extract_accounts_from_equipment` flattens the in-scope
  categories (PEA/PEA-PME, CTO, its cash pocket, Compte Courant, Livret —
  skipping life insurance/PER/mortgages/external) into raw account records;
  `fold_cash_pockets_into_securities_accounts` folds a CTO's linked cash
  pocket into its `cashBalance`. All three are tested against a synthetic
  fixture mirroring the real structure.
- The `apikey` header sent with every `api.fortuneo.fr` request (this
  GraphQL endpoint, and the REST APIs below) had the same value across both
  captures, hours apart — and is the same value submitted as `client_id`
  during the `/oauth-pkce/token` login step. The sidecar should capture it
  live from that login request rather than hardcoding it, so it stays
  correct if Fortuneo ever rotates it.
- `GET https://api.fortuneo.fr/fto-transaction-api/v1/accounts/{id}/transactions`
  returns clean transaction JSON (`label.simplifiedLabel`, `amount.value`,
  `bookingDate`) — confirmed both for a cash-type account and, from the
  full-site HAR, for a PEA: dividend/coupon cash receipts on a PEA holding
  do appear in this feed (identifiable only by their free-text label, e.g.
  a `"Div"`/`"TNC Div"`-prefixed `simplifiedLabel` — there is no dedicated
  `category` for dividends; observed `category.label` values are generic
  bank buckets like `"Epargne"`, `"Frais bancaires"`, `"Salaires"`,
  `"A catégoriser"`). **This endpoint is a cash-ledger, not a trade
  blotter**: it records the dividend landing as cash, not a security's buy/
  sell executions. Fortuneo has a separate "Carnet d'ordres" (order book)
  page, linked from account navigation, that was not investigated and is
  not wired into the connector — a full buy/sell trade history is not
  currently imported. Realized/unrealized gain is only available as each
  position's current P&L (see below), not as discrete trade records.
- **Per-position holdings (ticker/quantity/price) are confirmed** from a
  full-site HAR capture that happened to include the PEA's actual holdings
  view — see the dedicated subsection below.
- A CTO has its own linked cash pocket, Fortuneo `type.value`
  `cash-account` — this is **not** a standalone Picsou account; its balance
  must be folded into the parent CTO's `cashBalance`. A PEA/PEA-PME has no
  such separate entry (its cash pocket is implicit in its own total). The
  Equipment query has no explicit CTO↔cash-pocket link field;
  `fold_cash_pockets_into_securities_accounts` pairs them 1:1 when there is
  exactly one of each (the observed, and presumably common, case) and
  raises otherwise. A legacy HTML fragment
  (`GET /AsynchAjax?div0=as_afficherSyntheseComptesBourse.do_codeProfilsTIT,COT...`,
  parsed by `fortuneo_parser.parse_bourse_synthese_table`) *does* have an
  explicit DOM-adjacency link and is kept as a documented fallback for the
  ambiguous multi-CTO case — not yet wired into `main.py`, since there is no
  multi-CTO account available to verify it against.
- `sme-share-savings-plan` (PEA-PME) has no distinct Picsou type; it maps to
  `PEA` like the plain `share-savings-plan`. Giving it its own `AccountType`
  is a cross-cutting change (enum, migration, every investment-type check,
  the account-type picker) that belongs in its own PR, not this connector's
  — note the Equipment **category path alone cannot separate the two**
  (both sit under `shareSavingsPlan`), so it would have to be refined by
  each account's own `type.value`.
- The Livret category (`banking.savings` in the Equipment response) exists
  in the schema and was empty for the captured account (no Livret held) —
  its `type.value` string is therefore still unconfirmed, but
  `extract_accounts_from_equipment` classifies by category path rather than
  by that string, so a Livret is still correctly recognized as `SAVINGS`
  once one appears, without needing to know its exact type string.

**Confirmed from the real login and 2FA DOM** (shared directly, not from a
HAR — network capture can never reveal client-rendered form structure):

- Login form: `#LOGIN` (text, `autocomplete="username"`) and `#PASSWD`
  (password, `autocomplete="current-password"`), plain fields, no virtual
  keyboard. Submit button has visible text "Connexion". Selectors use the
  stable `id`/`name` attributes, not the CSS-module classnames (those are
  build-hashed and rotate on every deploy).
- 2FA screen: six individual single-digit inputs (no `id`/`name`, but a
  stable `input[inputmode="numeric"][pattern="[0-9]*"]` combination — same
  one-input-per-digit shape as Bourse Direct's OTP screen), submit button
  text "Suivant". Delivery mechanism (SMS/app) not stated by the capture;
  the sidecar just fills whatever code it's given.
- Success detection does **not** use the page URL (`mon-espace` is identical
  before and after login) — `main.py`'s `_wait_for_login_outcome` (during
  `/initiate`) and `_wait_for_token_exchange` (during `/complete`) instead
  poll for the `client_id` captured from the real `/oauth-pkce/token`
  request, which only fires on genuine success. There is still no confirmed
  selector for a rejected-credentials error message, so that case falls
  back to a timeout-based `INVALID_CREDENTIALS`; this path has not been
  observed against a real rejected login (only against dummy/non-account
  credentials, where it worked as designed).
- Resuming a session needs more than `context.storage_state()` — see
  "Verification boundaries" for the `sessionStorage` finding and the switch
  to running data-fetch calls inside the page's own JS context.

**Per-position holdings — confirmed from a full-site HAR capture** that
happened to include the PEA's actual holdings view:

- **Fortuneo runs two frontends** and lets users choose between them: the
  modern SPA (`/mon-espace`, OAuth/PKCE) and a legacy JSP site
  (`/fr/prive/…`, ordinary session-cookie auth). This distinction is
  load-bearing, not trivia — see the next three points and "Verification
  boundaries" bugs 15–16.
- **Reaching the legacy site is an explicit handshake.** Logging in
  through the SPA does *not* establish a legacy session. The frontend
  crosses over by submitting an **empty-body form `POST /ssoacces`** from
  a `/mon-espace` document, which 302-redirects to
  `/fr/prive/default.jsp?ANav=1` ("ancienne navigation") and mints the
  legacy session on the way. It must be a real form submission — a
  top-level navigation carrying the SPA origin/referer — not a `fetch()`;
  it authenticates purely on cookies already in the jar. Skipping this
  step lands on the legacy login form even seconds after a successful
  login (confirmed live, "Verification boundaries" bug 16).
- **The legacy site can be gated by a MiFID interstitial.** After the
  handshake, Fortuneo may serve a *"Créer votre profil investisseur"*
  prompt in place of every legacy page until it is answered. The connector
  takes **"Plus tard"** (defer) once on the way in — never "Créer", since
  this integration is read-only and must not submit anything on the user's
  behalf. Selected by button text; its classnames are build-hashed and
  rotate on deploy ("Verification boundaries" bug 18).
- **The page carries its own valuation summary**, and it is the authority:
  `Évaluation Titres`, `Solde espèces EUR`, `Valorisation totale`
  (`parse_portfolio_summary`). Parsed holdings must sum to the securities
  total within €1 or the snapshot is refused — which is what separates a
  genuinely empty account (zero securities, reconciles) from a table that
  failed to render (zero rows against a non-zero total). It also gives the
  account's cash directly, so a PEA's cash is read rather than derived
  ("Verification boundaries" bug 25).
- There is **no JSON API for positions**, and they exist only on the
  legacy side: `GET /fr/prive/mes-comptes/{segment}/situation/`, which
  returns ~200KB of **server-rendered** HTML with every holding as a
  `<tr name="…princip">` row. The modern Equipment GraphQL query has no
  position field at all.
- **What matters is being on the legacy frontend, not the request shape.**
  Once the `/ssoacces` handshake has run, an ordinary top-level navigation
  returns the page. Requesting it from the *modern SPA* — by navigation,
  XHR `fetch()`, or iframe — gets the SPA's own shell or login form
  instead, which is what made the request shape look decisive at first. Note also that `storage_state()` does not restore the
  SPA's OAuth token, so the SPA page itself can sit at a login form while
  the api.fortuneo.fr calls keep succeeding on their separately-captured
  `apikey` — API-authenticated but UI-unauthenticated, which is what made
  the earlier failures so confusing to read.
- `{segment}` is `pea`, `ppe` (PEA-PME), or `compte-titres-pea` (CTO) —
  confirmed in `fortuneo_parser.PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT`, keyed
  by each account's Equipment `type.value` (not by Picsou's collapsed
  `PEA` type, since PEA and PEA-PME need different segments). The
  `?ca={ca}&iframe=true` query string is carried by every real request in
  the HAR. **`ca` is the legacy site's own 32-hex account id, not the
  Equipment API's — they are different identifier spaces** — and is
  scraped from the legacy home page's account links
  (`parse_legacy_account_ids`). A wrong `ca` renders an empty portfolio
  rather than erroring ("Verification boundaries" bug 24). Unverified for a
  CTO with more than one account (same open question as the multi-CTO
  cash-pocket case above).
- Each holding is a `<tr name="{code}princip" class="l1"|"l2">` row (zebra
  striped); `{code}`'s trailing 12 characters are the ISIN (e.g.
  `FTN000026FR0000000OO0` → `FR0000000OO0`). `parse_portfolio_positions`
  extracts quantity, current price, average buying price (PRU), current
  EUR valuation, and P&L per row. `quoteCurrency` is hardcoded `"EUR"`: a
  PEA can only hold EU-domiciled, EUR-quoted securities by regulation; this
  has not been verified for a CTO, which can hold foreign-currency
  securities.
- **This parser was validated against the real captured page**, not just a
  synthetic fixture: all 14 real holdings extracted, and every row's
  price × quantity and (valuation − cost basis) reconciled to the cent
  against Fortuneo's own displayed figures (see "Verification
  boundaries"). Several blank `<td class="numb"></td>` cells (unused
  SRD-related columns) in the same rows would have been silently matched
  as the real value by a naive regex; the parser requires a leading digit
  in every numeric capture specifically because of this.
- A PEA's cash is *derived*, not scraped: `balanceEur − sum(positions)`,
  computed once positions are known (see "Portfolio collection contract").

**Still open:**

1. Whether the `INVALID_CREDENTIALS` timeout fallback is ever actually
   reached against a real (not dummy) rejected login, or whether Fortuneo
   shows a distinct error state worth its own selector.
2. `_fetch_positions` now performs the `POST /ssoacces` handshake, lands
   on the legacy frontend, and reads the situation page from an iframe
   opened there (bugs 15–27). The parser is verified against that
   exact 206KB response offline (14/14 positions, `quantity × price`
   reconciling to the cent), but the fetch path itself is **not yet
   re-confirmed live**, so the full chain (legacy home → iframe → parse)
   remains unconfirmed end-to-end.
3. Multi-CTO and CTOs holding foreign-currency securities (see above).

`services/fortuneo-auth/main.py`'s login, 2FA, account/transaction, and
position fetching are all implemented against everything confirmed above.
Login/2FA/account/transaction fetching are **verified end-to-end against a
live account**; position fetching is validated against real captured data
but not yet exercised through the live sidecar navigation — see
"Verification boundaries".

## Authentication and 2FA

`services/fortuneo-auth` is an isolated FastAPI/Playwright sidecar, following
the same contract as `services/bourse-direct-auth`:

1. `POST /initiate` opens the login page, fills `#LOGIN`/`#PASSWD`, and
   submits. A `context.on("request")` listener captures the `client_id`
   field from the `/oauth-pkce/token` request the page's own JS fires on
   success — this is the same value required as the `apikey` header on
   every subsequent `api.fortuneo.fr` data request, so it travels inside
   the `sessionState` envelope (`{"storageState": ..., "apiKey": ...}`)
   rather than being hardcoded.
2. If the 2FA screen appears instead of landing on `mon-espace`, the browser
   context is kept in memory for at most ten minutes and the caller
   receives a one-use `processId`.
3. `POST /complete` atomically claims that process and fills the six-digit
   code into the six digit inputs.
4. The sidecar returns Playwright's complete storage state. The Java backend
   encrypts it through `CryptoEncryption` before writing `fortuneo_session`.

The login, password and one-time code are never stored or logged. Pending
browser contexts are closed after completion, failure, expiry, sidecar
shutdown and by a periodic expiry sweep.

## Account type mapping

The primary classification is by **Equipment GraphQL category path**
(`fortuneo_parser._EQUIPMENT_CATEGORY_ACCOUNT_TYPE`), not by each account's
own `type.value` string — this is what lets a Livret map correctly to
`SAVINGS` even though its exact `type.value` was never observed:

| Equipment category | Fortuneo `type.value` (where confirmed) | Picsou type |
|---------------------|-------------------------------------------|-------------|
| `financialPortfolio.shareSavingsPlan` | `share-savings-plan` (PEA) / `sme-share-savings-plan` (PEA-PME) | `PEA` (no distinct Picsou type for PEA-PME) |
| `financialPortfolio.ordinarySecurities` | `ordinary-securities-account` | `COMPTE_TITRES` |
| `financialPortfolio.cash` | `cash-account` (CTO's linked cash pocket) | *not synced as its own account* — folded into the parent CTO's `cashBalance` |
| `banking.current` | `current-account` | `CHECKING` |
| `banking.savings` | *unconfirmed — no Livret in the captured account* | `SAVINGS` |

`financialPortfolio.lifeInsurance`, `retirementSavingsPlan` (PER),
`mortgages`, and both `external` categories (aggregated external accounts)
are out of scope and skipped. `account-api/v2/accounts`'s `productType`
field (`fortuneo_parser.ACCOUNT_TYPE_BY_PRODUCT_TYPE`) matches the same
`type.value` strings and is kept as a secondary/cross-check mapping, since
that REST endpoint is confirmed but no longer the primary data source. This
otherwise mirrors BoursoBank's mapping (`docs/features/bourso-bank.md`),
except for the CTO cash-pocket linkage, which is Fortuneo-specific.

## Portfolio collection contract

`AccountPayload.type` accepts all four types above. For `COMPTE_TITRES`,
completeness is established by reconciliation, identical to Bourse Direct:

```text
account total ~= cash + broker portfolio valuation
sum(position valuation in EUR) ~= broker portfolio valuation
```

The tolerance is the greater of EUR 0.05 and 0.1% of the expected amount. A
CTO's `cashBalance` is sourced independently (its real, linked "Compte
espèces" account, see "Cash accounts and transactions" below), so this
check is a genuine cross-check between two independently-sourced figures.

For `PEA`, there is no independent cash figure to cross-check against —
Fortuneo has no cash/position breakdown for a PEA anywhere, only its total
`balanceEur`. The sidecar instead **derives** `cashBalance = balanceEur -
sum(position valuations)` once positions are scraped
(`main.py`'s `/accounts` handler), so the reconciliation identity above
holds by construction rather than by independent measurement. This still
protects against a wrong/incomplete position scrape becoming a wrong
*total* (a missing position would show up as an implausible cash
remainder, e.g. an unexpectedly large or negative "cash" figure), but it
cannot catch a PEA position list that is merely misvalued while still
summing close to the truth.

For `CHECKING`/`SAVINGS`, there are no positions to reconcile against — the
contract instead requires `positions` to be empty and `cashBalance` to equal
`balanceEur`. `FortuneoSyncService.prepareAccounts` fails closed
(`INVALID_DATA`) if a cash account snapshot violates either invariant. This
is a generalization of the existing reconciliation invariant to accounts with
zero positions, not a new architectural decision — see
`docs/decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md` for the
original reconciliation design this extends.

## Cash accounts and transactions

Unlike Bourse Direct (investment accounts only), Fortuneo also syncs cash
account balances and transactions, following BoursoBank's precedent
(`docs/features/bourso-bank.md`):

- `positions` is always empty for `CHECKING`/`SAVINGS` accounts.
- Transactions replace the trailing 90-day window of non-manual rows on every
  sync (`FortuneoSyncService.replaceRecentTransactions`, mirrors
  `BoursoSyncService`'s transaction replacement at
  `BoursoSyncService.java:288-312`): existing transactions older than 90 days
  and any user-entered (manual) rows are kept; the rest of the non-manual
  window is deleted and replaced with the incoming snapshot.
- Transactions are validated (`date` and `amount` required) before being
  accepted, same fail-closed discipline as positions.

## Asynchronous import

Authentication stores the encrypted session in a short database transaction,
then queues portfolio work on the managed `fortuneoSyncExecutor`. Manual
`POST /api/fortuneo/sync` also returns `202 Accepted` immediately. The UI
polls `GET /api/fortuneo/status` while the state is `QUEUED` or `RUNNING`.

```text
IDLE -> QUEUED -> RUNNING -> SUCCESS
                         -> FAILED
```

Only one job can be queued or running for a member. A job carries the
database session ID that created it; a cleared or replaced session prevents
that old job from committing. Jobs interrupted by a backend restart are
marked `FAILED` on startup so the UI never remains stuck in an in-flight
state.

Stable failure codes are returned in RFC 7807 `code` fields and persisted as
`lastSyncError`: `INVALID_CREDENTIALS`, `INVALID_OTP`,
`AUTH_ATTEMPT_EXPIRED`, `SESSION_EXPIRED`, `PORTFOLIO_INCOMPLETE`,
`UPSTREAM_FORMAT_CHANGED`, `UPSTREAM_UNAVAILABLE`, `INVALID_DATA` and
`INTERNAL_ERROR`.

## Atomic persistence

Network calls, browser work and OpenFIGI resolution happen outside the
database transaction. Only after every account passes validation does a
short transaction:

- upsert accounts under stable `ft_` external IDs and provider `Fortuneo`;
- preserve user-soft-deleted accounts instead of recreating them;
- deduplicate positions that resolve to the same ticker;
- replace each account's holdings and trailing 90-day transaction window;
- flush the replacement before computing its daily snapshot;
- mark the session `SUCCESS`.

Any validation or persistence failure rolls the transaction back. The
previous holdings, transactions, account valuation and snapshot remain
coherent.

The daily 08:00 scheduler calls `resyncIfSessionActive` and goes through the
same queue and completeness gates.

## Deployment

Docker Compose builds `services/fortuneo-auth/Dockerfile`. The image uses
`python:3.12-slim-bookworm`, installs Chromium only and runs as a non-root
user, matching `docs/decisions/2026-04-25-tr-auth-sidecar-slim-image.md` and
Bourse Direct's precedent. The service is reachable only from the internal
Compose network. The backend URL defaults to `http://fortuneo-auth:8001` and
can be overridden with `FORTUNEO_AUTH_URL`; local development defaults to
`http://127.0.0.1:8003`.

The sidecar needs outbound HTTPS access to `www.fortuneo.fr` once
implemented. It does not need an ingress or any externally reachable port.

## Verification boundaries

CI builds the sidecar image and runs the parser and lifecycle tests inside it
(`.github/workflows/ci.yml`, `fortuneo-sidecar` job). Backend tests cover the
adapter contract, member scoping, asynchronous state transitions,
stale-session fencing, atomic replacement, the cash-account reconciliation
branch, and transaction window replacement. Frontend tests cover 2FA errors
and waiting for background success before closing the account wizard.

A real-account end-to-end test cannot run in public CI because it requires
private credentials and a human-delivered OTP — this is inherently a manual
maintainer step before each release, exactly as Bourse Direct's release
process requires.

**Login, 2FA, and data-fetching mechanics verified against a live account**:
`POST /initiate` → `POST /complete` (real OTP) → `POST /accounts` reached
all four account types (PEA, PEA-PME, CTO, Compte Courant) with correct
balances and real transactions on the checking account. Getting there took
several live iterations and caught six real bugs that no unit test could
have, since each depends on Fortuneo's actual runtime behavior:

1. `Locator.is_visible(timeout=…)` does not actually wait for an element to
   appear — despite the parameter name, it's a single immediate check.
   Form lookups now use `Locator.wait_for()`, which genuinely polls.
2. The SPA's URL (`mon-espace`) is identical before and after login, so
   URL-based success detection is worthless. Fixed to key off the captured
   `client_id` (populated only once the `/oauth-pkce/token` exchange
   actually fires).
3. A cookie-consent overlay (TrustCommander) intercepts clicks on anything
   underneath it, including the login submit button, until dismissed.
4. The OTP screen's digit boxes stay in the DOM (filled, with the submit
   button showing a loading spinner) while Fortuneo verifies the code —
   reusing the "is the OTP screen still showing" check to detect
   post-submit failure made `/complete` give up after a single instant
   poll instead of actually waiting for the result, misreporting an
   in-flight request as `INVALID_OTP`. Fixed with a dedicated
   `_wait_for_token_exchange` that only polls for the token exchange.
5. **`context.storage_state()` (cookies + localStorage) is not enough to
   resume a Fortuneo session** — restoring only that, even immediately
   after a successful login, showed the login form again. The missing
   piece is `sessionStorage`, which Playwright's storage_state never
   captures; the SPA evidently keeps its API auth token there. Fixed by
   manually reading/restoring `sessionStorage` alongside storageState (see
   `_read_session_storage`/`_restore_session_storage`), and by making the
   actual data-fetch calls run inside the page's own JS context via
   `page.evaluate()` (`_page_fetch`) rather than Playwright's out-of-band
   `context.request`, so they automatically pick up whatever the SPA
   attaches to real requests without needing to reverse-engineer the exact
   mechanism.

6. **A PEA's `cashBalance` was defaulting to its full `balanceEur`** (100%
   cash) in `fold_cash_pockets_into_securities_accounts` — valid for
   CHECKING/SAVINGS, but a PEA can hold real stock positions with no
   separate "cash pocket" account to source a true figure from (unlike
   CTO). This silently misreported the live PEA tested (which almost
   certainly holds real positions) as pure cash. Caught on review after the
   successful run, not by the run itself. Fixed to report
   `cashBalance = None` for a PEA instead of guessing, which correctly
   makes `/accounts` fail the whole call closed (`PORTFOLIO_INCOMPLETE`)
   whenever a PEA is present, until per-position holdings exist to
   determine the real split.

7. **`POST /complete` captured `storage_state()`/`sessionStorage`
   immediately upon seeing the token-exchange `client_id`, with no settle
   time** — first caught running the full flow through the real app
   (frontend → backend → sidecar → sidecar's own background sync) rather
   than a hand-chained curl script: `/complete` returned 200 in ~1.15s,
   the async sync's `/accounts` call fired ~80ms later, and the *first*
   authenticated request (Equipment) already came back 401
   `SESSION_EXPIRED`. `/accounts`'s own navigation already had a
   `wait_for_load_state("networkidle")` after `page.goto()` specifically
   because the SPA keeps client-side-redirecting for a moment post-login
   (see bug 5's `_page_fetch` fix); `/complete` never got the equivalent
   treatment, so a still-settling redirect (e.g. login page → dashboard)
   could be captured mid-transition, missing a cookie or `sessionStorage`
   key the dashboard only sets once fully loaded. Fixed by adding the same
   networkidle wait between the token-exchange success and reading
   session/storage state. Also added a diagnostics capture
   (`_capture_failure_diagnostics`) on `/accounts`'s `SESSION_EXPIRED`
   path, which previously only fired on `INVALID_OTP` — a restored-session
   rejection had no visibility into what the page actually showed.
   **Confirmed fixed**: a subsequent live run got past login/2FA/accounts
   cleanly with no `SESSION_EXPIRED`, reaching `_fetch_positions` for the
   first time (see bug 8).
8. **`_fetch_positions`'s `page.content()` raced the page still
   navigating** — the first live exercise of the position-scraping
   `page.goto()` path (unlocked by fixing bug 7), hit
   `playwright._impl._errors.Error: Page.content: Unable to retrieve
   content because the page is navigating and changing the content.`
   immediately after `page.goto(url, wait_until="domcontentloaded")`.
   Likely an SSO hop from the SPA's session into the legacy JSP-based
   `/situation/` page that isn't done settling by the time
   `domcontentloaded` fires — the same family of race as bug 5, just on a
   different page. Fixed with the same `wait_for_load_state("networkidle")`
   grace period used elsewhere, plus a retry-once on `page.content()`
   itself (mirroring `_page_fetch`'s retry-on-"Execution context was
   destroyed"), since the "page is navigating" error is specifically a
   transient read-time race that a settle wait alone doesn't fully
   eliminate. **Fix confirmed working**: the next live run had no
   navigation error at all -- but see bugs 9 and 10, which that same run
   exposed further down the same code path.
9. **`_fetch_positions` returned an empty list live with no error**, for
   an account confirmed (bug 6's HAR analysis) to hold ~14 real positions
   worth a large sum. Nothing caught it: `parse_portfolio_positions` finding
   zero `<tr ...princip...>` rows isn't distinguishable from "this account
   genuinely holds no securities" at the parser level, so it just returned
   `[]`, and the caller's PEA cash derivation
   (`cashBalance = balanceEur - sum(positions)`) silently collapsed back
   to reporting 100% cash -- **the exact bug 6 failure mode, recurring
   from a different cause**, and it reached the database this time (synced
   "successfully": 4 accounts, 0 holdings). Fixed in two parts: (a)
   `_fetch_positions` now treats an empty parse result as fatal
   (`PortfolioFormatError`, mapped to `INVALID_DATA`) rather than a valid
   answer, capturing diagnostics first since a truly-empty PEA and a
   scrape failure are otherwise indistinguishable and worth investigating
   either way; (b) confirmed via the diagnostics this produced.
10. **Diagnostics from bug 9 showed the real cause: the page had been
    silently bounced to the login form** (`https://mabanque.fortuneo.fr/
    mon-espace`, showing the credentials form) — a 200, not a 401/403, so
    nothing in the existing status-code check caught it. A session good
    enough for Equipment/transactions (bearer-style `apikey` header
    auth) was rejected for this specific legacy cookie-session route.
    Reproduced identically 3 times in a row (deterministic, not a flaky
    race). First fix attempt: passed an explicit `referer` on the
    `page.goto()` pointing at `/mon-espace`, since Playwright's `goto()`
    sends no Referer by default whereas a real in-app click would have;
    also added an explicit login-form check right after navigating so a
    redirect-to-login reports `SESSION_EXPIRED` rather than silently
    falling through to "no position rows found". **The referer did not
    fix it** — confirmed on a fresh login/2FA cycle (not a stale session):
    `/complete` succeeded, but the very next `_fetch_positions` call was
    bounced again, this time correctly logged and reported as
    `SESSION_EXPIRED` rather than silently mis-scraped. See bug 11 for the
    actual fix.
11. **Root cause: it was the navigation itself, not anything about the
    request.** Equipment/transactions authenticate fine via
    `page.evaluate()`-driven `fetch()` (see bug 5) — a plain HTTP call
    riding on the page's cookies, rendering nothing. `_fetch_positions`
    was the only caller still using `page.goto()`, a full top-level
    navigation that plausibly re-runs whatever client-side login/
    route-guard check the SPA performs when a route "loads" — a check a
    same-origin fetch() never triggers at all. Fixed by rewriting
    `_fetch_positions` to fetch the page as HTML text via `_page_fetch()`,
    the exact same mechanism already proven reliable for Equipment/
    transactions, instead of navigating to it. Since `fetch()` follows a
    server-side redirect chain transparently, a rejected session still
    comes back with a 200 whose body is the login page's HTML — detected
    by checking the fetched text for `LOGIN` field markers, the fetch-based
    equivalent of bug 10's DOM check (which no longer applies: there's no
    live page to inspect once nothing navigates). This also eliminates
    bugs 8 and 10 by construction: no navigation means no
    page-still-navigating race, and no navigation means no route-guard to
    be bounced by. **Confirmed fixed on the next live run: no more
    login-form bounce at all** — but the fetch now returned 200 with a
    *different* empty result (see bug 12).
12. **The fetch was hitting the wrong content, not failing.** The
    "no position rows found" diagnostic this time was Fortuneo's *modern*
    SPA app shell (`<div id="layout"></div>` plus a pile of JS bundle
    preloads) — a ~2.7KB stub, not the legacy page at all. Root cause,
    found by re-checking the original HAR more carefully: every real
    request to this route in the capture carried
    `?ca={webId}&iframe=true` — confirmed by finding the exact same string
    as both the URL's `ca` value and the Equipment response's `webId`
    field. `PORTFOLIO_URL_TEMPLATE` never had this query string; every
    previous attempt (both the `page.goto()` versions and bug 11's
    `fetch()` rewrite) was missing it. Without `?ca=&iframe=true`, the
    now-current default frontend owns the bare URL and serves its own
    shell; with it, the request reaches the legacy `iframe=true` fragment
    the shell itself would otherwise embed — which also plausibly explains
    bug 10's login-form bounce for the `page.goto()` versions: the *outer*
    page needs a session bootstrap the plain fragment doesn't require.
    (Context confirmed by the user: Fortuneo currently offers a choice
    between an old and a new frontend, and the original HAR was captured
    on the old one — consistent with the modern shell now being what a
    bare URL resolves to by default.) Added `?ca={web_id}&iframe=true` to
    `PORTFOLIO_URL_TEMPLATE`, threading each account's already-known
    `webId` through to `_fetch_positions`. **Did not fix it**: the next
    live run returned byte-identical shell HTML, with or without the
    query string — see bug 13.
13. **The query string was never the mechanism — how the request looks
    to the server is.** Diffing the bug 12 and bug 9 diagnostic HTML dumps
    confirmed they were byte-for-byte identical, disproving the
    query-string theory outright. The remaining difference between the
    three attempts made so far is *how* each one requests the URL:
    `page.goto()` sends a real top-level navigation (`Sec-Fetch-Dest:
    document`) and gets bounced to login; `page.evaluate()`-driven
    `fetch()` sends an XHR (`Sec-Fetch-Dest: empty`) and gets the SPA
    shell; neither reproduces what the modern frontend itself actually
    does to render this legacy content, which — per the `iframe=true`
    param's own name — is embed it in a real `<iframe>`
    (`Sec-Fetch-Dest: iframe`). Fixed by injecting an actual same-origin
    `<iframe>` into the page via `page.evaluate()`, pointing it at the
    `?ca=&iframe=true` URL, and reading back
    `iframe.contentDocument.documentElement.outerHTML` once it loads. This
    has no HTTP status to check (an iframe "loads" successfully even when
    its content is a login page), so the login-bounce detection is
    content-only, same as bug 10/12's check. **Confirmed partially fixed**:
    the next live run's diagnostic dump showed real signs of the correct
    app actually running inside the iframe (a dynamically-injected Instana
    monitoring script and stylesheet, only added by client-side JS after
    bootstrap) — but still zero position rows. See bug 14.
14. **Reading the iframe too early.** Diffing bug 13's diagnostic against
    bug 9's showed the HTML was no longer identical (unlike bug 12) —
    normalized attribute quoting plus two elements only client-side JS
    adds. `iframe.onload` fires once the initial document finishes
    *downloading*, not once everything has settled, so the one-shot
    `outerHTML` read caught the frame mid-bootstrap. Fixed by rewriting
    `_fetch_via_iframe` to use Playwright's `Frame` API instead of a single
    opaque `page.evaluate()` blob: `page.frames` picks up a dynamically
    injected iframe once it attaches, giving the same
    `wait_for_load_state("networkidle")` used elsewhere plus
    `frame.content()` (settled DOM) rather than reaching into
    `contentDocument` from JS, with a best-effort wait for an actual
    `tr[name$="princip"]` row before reading. Necessary, but still not
    sufficient — the next run returned a fully-rendered **login form**
    (18KB) despite a login having completed seconds earlier. See bug 15.
15. **Root cause, finally: we were on the wrong frontend the entire time.**
    Fortuneo runs *two* frontends and lets users choose between them — the
    modern SPA (`/mon-espace`, OAuth/PKCE token auth) and a legacy JSP site
    (`/fr/prive/…`, ordinary session-cookie auth). Every attempt so far had
    requested the positions URL while the browser sat on the SPA, so the
    SPA answered — with its shell, or its login form. Two facts explain the
    whole saga: (a) `storage_state()` restores the legacy site's session
    *cookies* but not the SPA's OAuth token, so the SPA page is genuinely
    logged out even while the api.fortuneo.fr calls succeed (those carry
    the separately-captured `apikey`) — which is why bug 10's "bounced to
    login" looked so contradictory; and (b) positions exist only on the
    legacy side. Found by pulling the *request headers* of the one HAR
    entry that actually returned holdings, rather than just its URL:
    `referer: …/fr/prive/default.jsp?ANav=1` (`ANav` = "ancienne
    navigation", the legacy site's entry point) and `sec-fetch-dest:
    iframe`, with a 206KB **server-rendered** response — disproving bug
    14's client-rendered conclusion too. Fixed by opening a second page on
    `default.jsp?ANav=1` (`_open_legacy_frontend`, lazily, only when a
    securities account exists, and on its own page so it can't disturb the
    SPA page's JS context) and loading the situation URL as an iframe from
    *there*, exactly reproducing the confirmed request shape. Offline
    validation against that 206KB HAR response: **14/14 positions parsed,
    every row's `quantity × price` reconciling to the cent** — proving the
    parser was never at fault, only the fetch. The live run then bounced at
    the legacy home page itself (see bug 16), on an assumption made here
    that turned out to be wrong: that an SPA login already implies a legacy
    session.
16. **Crossing to the legacy frontend is an explicit handshake.** Bug 15
    assumed `storage_state()`'s cookies were enough to walk straight onto
    `default.jsp?ANav=1`. Live: Equipment succeeded (so the session was
    genuinely fine) and the legacy home page *still* returned a login form,
    seconds after a successful login. Replaying the HAR in chronological
    order around that page showed the step in between:
    `POST /ssoacces` → `302` → `/fr/prive/default.jsp?ANav=1`, issued right
    after the `oauth-pkce/token` exchange. It carries an **empty body**
    (`content-length: 0`) with `sec-fetch-mode: navigate` and
    `referer: …/mon-espace` — i.e. a real `<form>` submission from an SPA
    document, authenticating purely on cookies, that mints the legacy
    session on the way through. Fixed by having `_open_legacy_frontend`
    land on `/mon-espace`, inject and submit that empty form
    (`_SSO_SUBMIT_SCRIPT` — a real form, not `fetch()`, so it is a
    top-level navigation with the right origin/referer), and wait for the
    redirect into `/fr/prive/…`. **Confirmed reached live** — the handshake
    now runs — but it immediately hit bug 17.
17. **`Execution context was destroyed` submitting the SSO form.** Bug 16's
    handshake failed live with exactly the error `_page_fetch` already
    guards against (bug 5's family), for two compounding reasons, both
    fixed: the new code path went `goto(domcontentloaded)` → `evaluate()`
    with **no `networkidle` settle in between**, so the SPA's own
    client-side redirect tore the context down mid-call (the main
    `/accounts` page has always had that settle; this path never got it);
    and `form.submit()` ran inline, so the navigation it triggers raced
    `evaluate()`'s own return value. The submit is now deferred a tick
    (`setTimeout(…, 0)`) so `evaluate()` returns first. **Confirmed fixed
    live**: the handshake completed, the legacy home page loaded, and no
    login bounce occurred — reaching the legacy frontend for the first
    time. That immediately exposed bug 18.
18. **A MiFID interstitial gates the whole legacy site.** With the
    handshake finally working, the portfolio iframe came back as a 14KB
    page titled *"Informations personnelles"* — Fortuneo's
    *"Créer votre profil investisseur"* prompt (a `front-authz`
    micro-frontend rendered into a `<shell-event-bridge>` element),
    offering **"Plus tard"** / **"Créer"**. Until it is answered it is
    served in place of *every* legacy URL, portfolio page included, which
    is why the fetch succeeded yet contained no position rows. Fixed by
    taking **"Plus tard"** once on the way in
    (`_defer_investor_profile_gate`, called from `_open_legacy_frontend`
    after the handshake), with a safety net inside `_fetch_via_iframe` in
    case it re-appears within the frame. Deliberately *defers* rather than
    completes the profile: this connector is read-only and must never
    submit anything on the user's behalf — "Plus tard" only postpones a
    prompt the user can still action themselves in the real UI. Matched by
    button text, since the element's classnames are build-hashed
    (`css-1d850bn`) and rotate on every deploy, exactly like the login
    form's. An empty parse that still contains `profil investisseur` now
    reports that distinctly, rather than as a generic "no position rows".
    **Diagnosis confirmed live** (the new distinct error fired) but the
    dismissal itself did not — see bug 19.
19. **The click could never fire: the iframe was `display: none`.** Bug
    18's gate was correctly *detected* live, yet no "deferred" log line
    appeared. Cause: `_INJECT_IFRAME_SCRIPT` hid the iframe with
    `display: none`, so it renders nothing and every element inside it is
    permanently invisible — Playwright will not click an invisible element,
    so the dismissal silently timed out every time. Fixed by positioning
    the iframe off-screen (`position: fixed; left: -10000px`) at a real
    1280×900 size instead: it still lays out and paints, so its contents
    behave normally, and a desktop-sized viewport also keeps the legacy
    page on its table markup rather than a responsive variant. Deferring
    the gate redirects the frame to the legacy home page, so
    `_fetch_via_iframe` now re-requests the original URL afterwards. **Did
    not fix it**: the gate was still detected and still never clicked — see
    bug 20.
20. **The dismissal swallowed its own failure.** After bug 19 the click
    still never fired, and `_defer_investor_profile_gate` could not say why:
    it wrapped `wait_for(state="visible")` + `click()` in a bare
    `except: return False`, making "not gated" (the normal case) and "gated
    but the click was vetoed" indistinguishable — so three live runs
    produced no evidence at all. Confirmed from the captured HTML that the
    button is ordinary light DOM (no shadow root, no `<template>`), so
    Playwright *can* see it; the failure is therefore an actionability veto,
    not a selector problem. Hardened: wait for `attached` rather than
    `visible`, then a normal `click()`, then fall back to
    `dispatch_event("click")` — which bypasses Playwright's visibility /
    hit-testing / stability checks entirely. Those checks are far likelier
    to veto a React micro-frontend button inside an off-screen iframe than
    the element is to be genuinely unusable. Crucially, the two outcomes
    are now distinguished in the logs: absent stays quiet, present-but-
    undismissable logs a warning. **This immediately paid off** — see bug 21.
21. **The gate dismissal and the iframe are mutually exclusive.** Bug 20's
    instrumentation resolved the mystery in one run: the log showed
    `button present but not clickable; dispatching a click event directly`
    followed by `interstitial deferred` — so Playwright's actionability
    checks really were vetoing the normal `click()`, and `dispatch_event`
    really does go through. But the run still failed, now at
    `frame.goto()` and again in the iframe cleanup, both with *"Execution
    context was destroyed"*: taking "Plus tard" navigates the **top-level
    page**, which tears the iframe (and its execution context) out from
    under the code holding it. The iframe approach and dismissing the gate
    cannot coexist. Resolved by dropping the iframe entirely: with the
    /ssoacces handshake in place the legacy session is real, so an ordinary
    top-level `page.goto()` reaches the page — the earlier attempts that
    justified the iframe (bugs 10–13) all predate the handshake and were
    really *unauthenticated* rather than wrongly-shaped requests. The new
    `_load_legacy_page` navigates, clears the gate if present, and
    re-requests the URL once afterwards (the dismissal itself navigates
    away). `_fetch_via_iframe` and its two injection scripts were deleted
    rather than left dormant. Not yet re-confirmed live — and since each
    live attempt costs a real 2FA code, the navigation now *falls back* to
    an iframe load when it returns no holdings, giving one run two shots at
    the two candidate shapes.
22. **An empty securities account would have failed the whole sync.** A
    third HAR, captured while browsing the real UI everywhere, showed the
    same account's CTO and PEA-PME situation pages returning ~27KB with
    **zero** position rows — they are genuinely cash-only (confirmed by the
    user). Bug 9's fail-closed rule treated *any* empty parse as a scrape
    failure, so those two accounts would have aborted every sync with
    `INVALID_DATA`, regardless of the fetch working. The two cases are
    indistinguishable from the row count alone, so they are now separated
    by `PORTFOLIO_RENDERED_MARKER` (`id="valorisation`): present in all
    three real situation pages (the 14-holding PEA *and* both empty ones),
    absent from every failure page captured live (login form, investor-
    profile gate, SPA shell). Rendered-and-empty returns `[]`; not-rendered
    still fails closed. A structural id rather than visible French copy, so
    wording changes can't silently reclassify a failure as an empty
    account. Covered by two regression tests.
23. **What the third HAR settled.** It confirms the real UI requests this
    page *only* as `sec-fetch-dest: iframe` with
    `referer: .../default.jsp?ANav=1` — the one shape ever observed
    returning the full document, which is why the iframe was kept as a
    fallback rather than deleted outright. It also shows that session was
    never gated at all (the interstitial's text appears only inside a JS
    bundle, never as a served page), which is why the gate blocks our fresh
    automated sessions but never appeared during manual browsing.
24. **`ca` was the wrong identifier all along.** With every other layer
    working (handshake, legacy frontend, gate cleared, correct page —
    *"Portefeuille temps réel"*), the portfolio still came back with no
    holdings, at 81KB against the real 206KB. Cause: the legacy site keys
    accounts by its own **32-hex** `ca` (`a 32-hex id`), while the modern
    Equipment API's `id` is a **22-char base64-ish** token
    (`Ab1Cd2Ef3Gh4Ij5Kl6Mn7O`) — *different identifier spaces*, with no
    overlap in any capture. Bug 12 had added `?ca={webId}` on the strength
    of finding one HAR string that looked like a match; it wasn't. Worse,
    a wrong `ca` doesn't error — the page renders normally, just empty —
    which is precisely the silent-wrong-data shape this connector is built
    to refuse. The legacy id is only discoverable on the legacy side: the
    home page links every account as
    `/fr/prive/mes-comptes/{segment}/situation/?ca={ca}`. Fixed by scraping
    that mapping (`parse_legacy_account_ids`, validated against the real
    home page: all three securities segments resolve) and threading it
    through instead of `webId`; the transactions API keeps using the
    Equipment id, which is correct there. Missing links now fail closed
    (`UPSTREAM_FORMAT_CHANGED`) rather than silently fetching an empty
    portfolio. Four regression tests, including one asserting an
    Equipment-style id is never accepted as a legacy one.
25. **The empty-account allowance became a mask.** With the right `ca` the
    PEA finally rendered its real page — and still parsed to zero rows, so
    bug 22's "rendered but empty" allowance accepted it and reported an
    a PEA holding real securities as 100% cash. Exactly the failure bug 6 fixed, reintroduced by
    the fix for bug 22: a marker that proves the *page* rendered says
    nothing about whether its *table* did. Replaced with a real
    reconciliation, using a summary table the page carries all along
    (`Évaluation Titres` / `Solde espèces EUR` / `Valorisation totale`).
    `parse_portfolio_summary` reads it, and the parsed holdings must sum to
    the page's own securities total within €1 or the snapshot is refused.
    This subsumes the marker heuristic entirely: an empty account reports
    zero securities and reconciles, while an unrendered table reports zero
    rows against a non-zero total and fails closed. Verified against all
    three real pages (a populated PEA whose rows summed to its stated securities
    total exactly; both empty accounts → 0,00). It also supplies **`Solde espèces EUR` directly**, so a PEA's
    cash is now read from the same snapshot as its holdings rather than
    derived by subtraction (bug 6's workaround) — and a disagreement with
    the Equipment cash pocket is logged. Five regression tests.
26. **`ObjectOptimisticLockingFailureException` committing transactions.**
    With the sidecar finally returning a full snapshot, the *backend*
    failed: `replaceRecentTransactions` deleted every non-manual row and
    re-saved the ones outside the 90-day window, but those are managed
    entities whose rows had just been deleted, so re-saving merged onto a
    missing row (`StaleObjectStateException` — note its "or unsaved-value
    mapping was incorrect"). It only bites once an account has history
    older than the window, which is why it never showed up until a sync got
    this far. Replaced with a windowed delete
    (`deleteByAccountIdAndIsManualFalseAndDateGreaterThanEqual`): older rows
    are simply never touched, so nothing needs re-saving and their ids stay
    stable. **`BoursoSyncService` has the identical pattern** and the same
    latent bug; deliberately left alone rather than changed blind, since it
    is a separately-verified connector — noted here as known work.
27. **`&nbsp;` vs U+00A0 — the same page in two representations.** The
    iframe fallback returned the genuine article at last: 209KB, 14 holding
    rows, summary intact. It was still rejected, because every numeric
    pattern had been written against *raw HTTP text*, where Fortuneo's
    thousands separator is a literal U+00A0 — while Playwright's
    `content()` returns *browser-serialised DOM*, where it is the entity
    `&nbsp;`. `75&nbsp;463,36` matched nothing, so a page full of holdings
    read first as "no summary", and would have read as an empty account
    without bug 25's reconciliation. Every offline validation to this point
    used HAR text, so nothing could have caught it. Fixed centrally with
    `_normalize_nbsp` at the entry of both parsers, folding **only** that
    entity (a blanket `html.unescape` would turn `&lt;` into a real `<` and
    invent tags). The existing P&L pattern spelled `&nbsp;` literally and
    broke under the normalisation — caught by an existing test, and now
    covered by two more asserting the two forms parse *identically*.
    Verified against the actual failing capture: every holding row parsed,
    summing exactly to the page's own securities total, cash read from its
    own line, and `quantity × price` matching on every row — byte-identical results from both representations.
28. **The UI understated the PEA by the value of its unlisted holdings.** The sync itself was
    correct — the account row, all 14 holdings and their broker valuations
    were stored exactly right. The loss was downstream, in
    `AccountService.liveBalanceEur`, which recomputes an investment account
    as `cash + Σ(quantity × live price)` and *skips* any holding
    Yahoo/OpenFIGI cannot price. Three of the PEA's holdings are unlisted
    securities (`titres non cotés`, the `QS…` codes that also appear in the
    transaction feed) that no public price provider will ever cover, so
    the whole value of those holdings silently vanished from the total. The guard for this already
    existed — `if ("Bourse Direct".equals(provider) && !allHoldingsPriced)
    return account.getCurrentBalance()` — but was hardcoded to one
    provider. Fortuneo is the same case and more permanently so: for Bourse
    Direct it covers a transient pricing outage, whereas here the
    instruments are structurally unpriceable. Generalised to an
    `AUTHORITATIVE_TOTAL_PROVIDERS` set. The first attempt regressed manual
    accounts — `Set.of(...).contains(null)` throws, where the
    `String.equals` it replaced was null-safe — caught immediately by an
    existing test and fixed with a null guard. Two regression tests.

29. **Duplicate transactions, found by code review rather than a failure.**
    `replaceRecentTransactions` deleted only inside the 90-day window but
    inserted *every* transaction the provider returned, including older
    ones — so each sync appended another copy of the same historical rows.
    Three copies deep in the database before it was noticed, and it would
    never have surfaced as an error. Introduced by bug 26's windowed-delete
    fix, though the delete-all-and-re-save version it replaced had the same
    mismatch. Fixed by filtering the insert to the same window that is
    deleted, so the two scopes always agree; existing duplicates were
    cleaned up separately. Covered by a regression test that feeds one
    in-window and one out-of-window transaction and asserts only the former
    is written.

**Review pass** (after the first successful live sync) also produced:
`_fetch_positions` now tries the iframe *first* — live, the plain
navigation failed for every account and the iframe succeeded for every
account, so the old order paid for a doomed request each time and made the
sync ~3× longer than needed; the navigation is kept purely as the one
thing that can clear the investor-profile gate. Each candidate document is
now parsed once (`_portfolio_snapshot`) rather than up to three times, and
an unreachable trailing `return` in `_load_legacy_page` was replaced with
an explicit `AssertionError`. `replaceRecentTransactions`'s early return on
an empty response is now documented as deliberate: an empty fetch is far
likelier to mean "nothing retrieved" than "everything was reversed", and
clearing real history on a thin response is unrecoverable from here.

**Sync duration.** The first working sync took 78s; reordering to try the
iframe first (the only shape that ever succeeds live) brought it to 45s, and
two timeout fixes target the rest. Both were waits for something that is
normally absent, so they always ran to their full cap:

- `_fetch_via_iframe` waited up to 10s for a holding row
  (`tr[name$="princip"]`). A cash-only securities account has none, so this
  burned the whole timeout on every empty account — 20s per sync for the
  captured account's CTO and PEA-PME. It now waits for
  `PORTFOLIO_SUMMARY_SELECTOR` (`#valorisation_compte`), the valuation block
  present on *every* portfolio page, empty or not, and absent from the login
  form, the interstitial and the SPA shell. A test pins the coupling: the
  element waited on must be the one the summary is parsed from.
- `_defer_investor_profile_gate` waited 8s for the gate. It was raised from
  4s while diagnosing bug 20, when "absent" and "present but unclickable"
  were indistinguishable; that is resolved, and the gate is absent on most
  loads, so it is back to 2s.

Remaining cost is mostly `networkidle` (eight calls, 15–20s caps): Fortuneo's
SPA runs analytics and polling that rarely go quiet, so those waits often run
long. Replacing them with element-specific waits is the next lever if the
sync needs to be faster still.

`_capture_failure_diagnostics` (screenshot + visible page text, saved to
`/tmp/fortuneo-debug` inside the container) was added during this process
and is worth keeping — it turned an opaque `INVALID_OTP` into a screenshot
that immediately showed the real cause (a loading spinner, not a rejected
code).

**Per-position holdings implemented and validated against real captured
data.** Bug 6 above meant a PEA could never sync until its cash could be
determined; positions are what make that possible
(`cashBalance = balanceEur - sum(positions)`, see "Portfolio collection
contract"). `parse_portfolio_positions` was validated directly against the
real `.../pea/situation/` HTML captured in the same full-site HAR that
prompted this work: **all 14 real holdings parsed**, and for every single
one, `quantity × currentPrice` matched the displayed valuation and
`valuation − (quantity × averageBuyPrice)` matched the displayed P&L, both
within a few cents (rounding on Fortuneo's displayed 3-decimal average
price). The sum of all 14 valuations (S EUR) subtracted from the
account's total balance (from the earlier live Equipment query) left a
plausible, unremarkable cash remainder — not
a suspicious leftover, which is the best available confirmation that both
data sources (the live Equipment API total and the separately-scraped
position page) agree with each other. The live sidecar navigation to fetch
positions has cleared the `page.content()` race (bug 8) but has not yet
completed end-to-end: the account being tested against is bounced to the
login form for this specific route regardless (bugs 9–10), a session/
cookie-scope issue distinct from page-load timing. That remains the open
verification item, alongside re-confirming the account/transaction paths
still succeed on a clean run after all four fixes.

## Key files

- `services/fortuneo-auth/main.py` — sidecar: FastAPI app, pending-context
  lifecycle, login/2FA form automation, Equipment/transactions/position
  fetching, request/response contract.
- `services/fortuneo-auth/fortuneo_parser.py` — French-number decimal
  parsing (reused from Bourse Direct's `portfolio_parser.py`), Equipment
  GraphQL multipart merging, account extraction and cash-pocket folding,
  and `parse_portfolio_positions` for per-position holdings.
- `backend/src/main/java/com/picsou/port/FortuneoPort.java` — port interface
  and typed records (`AccountData`, `Position`, `Transaction`).
- `backend/src/main/java/com/picsou/adapter/FortuneoAdapter.java` — calls
  the sidecar via `WebClient`.
- `backend/src/main/java/com/picsou/service/FortuneoSyncService.java` — auth
  orchestration, account/holding/transaction upsert, scheduled sync.
- `backend/src/main/java/com/picsou/controller/FortuneoController.java` —
  REST under `/api/fortuneo/`.
- `backend/src/main/java/com/picsou/model/FortuneoSession.java` — session
  entity (encrypted session state, sync status state machine).
- `frontend/src/pages/sync/FortuneoTab.tsx` — dedicated sync page tab.
- `frontend/src/components/sync/FortuneoPanel.tsx` — auth form + sync
  controls, shared by the tab, `AddAccountModal`, and the setup wizard.
- `frontend/src/components/sync/SyncAllModal.tsx` — inline reconnect card
  (shown once a Fortuneo account exists), following BoursoBank's pattern.
- `frontend/src/features/sync/api.ts` — `fortuneoApi`.
- `frontend/src/features/sync/hooks.ts` — `useFortuneoStatus`,
  `useInitiateFortuneoAuth`, `useCompleteFortuneoAuth`, `useSyncFortuneo`,
  `useClearFortuneoSession`.

## Links

- [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- [Bourse Direct isolated browser sidecar and atomic complete snapshots](../decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md) — reconciliation and async-job pattern this connector extends
- [tr-auth as isolated sidecar with Chromium-only image](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md) — slim image pattern reused for the Fortuneo sidecar
- [Bourse Direct sync](./bourse-direct.md)
- [BoursoBank sync](./bourso-bank.md) — cash-account and transaction handling precedent
- [Encryption at rest](./encryption-at-rest.md)
