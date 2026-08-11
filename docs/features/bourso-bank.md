# Feature: BoursoBank sync

> Last updated: 2026-08-11
> Status: ⚠ **Not yet validated against a live account** — the contract is taken
> from a maintained reference implementation rather than guessed, and every
> deterministic boundary is tested, but nobody has signed in for real yet. See
> "Verification boundaries".

## Context

Enable Banking covers BoursoBank's payment accounts but cannot reach the **PEA
or the compte-titres**: PSD2 does not cover securities accounts, so the envelope
that often holds the largest balance was invisible to Picsou. This connector
signs in to BoursoBank's customer website and imports, per account: the current
accounts, the livrets, and the PEA/CTO with their cash, their total and every
open position.

It is unofficial, read-only, and fails closed.

### A note on the previous attempt

A BoursoBank connector shipped dormant in 1.0.0 and was rewritten wholesale in
August 2026. It had been written against markup that does not exist: the virtual
keyboard was parsed as if the digit were the button's text (it is a base64 SVG),
the account list was parsed from `data-account-*` attributes (there are none),
the trading path was `_user__{hash}__` (it is `_user_/_{hash}_/`), and every
failure was swallowed with `return []`. None of it could ever have worked. If
you are looking at git history and wondering why the diff is a rewrite, that is
why.

## Scope

| Imported | Not imported |
|---|---|
| Current accounts → `CHECKING` | Loans (`data-summary-loan`) |
| Livrets, LEP → `SAVINGS` / `LEP` | Accounts BoursoBank aggregates from **other banks** |
| PEA, PEA-PME → `PEA` | Transactions |
| Compte-titres → `COMPTE_TITRES`, with positions | Orders, statements |

**PEA-PME folds into `PEA`**: Picsou has no separate envelope for it, and the
two share a tax regime and a reporting shape.

**Third-party accounts are deliberately excluded.** BoursoBank's dashboard also
lists accounts it aggregates from other banks, tagged with their real bank in
`c-info-box__account-sub-label`. Importing them would duplicate an Enable Banking
connection with worse freshness, so anything whose sub-label is not BoursoBank is
skipped — and *counted*, then logged, because a connector that quietly drops
accounts is indistinguishable from one that is broken.

**Transactions are out of scope**, the same exclusion Bourse Direct, Amundi and
DEGIRO make. BoursoBank's CSV export moved behind a CSRF-protected POST in July
2026 and its columns shift depending on whether anything in the range is tagged;
it is a connector of its own, not a free extra. The generic CSV importer
([csv-transaction-import.md](./csv-transaction-import.md)) still accepts
BoursoBank's own export.

⚠️ **Overlap with Enable Banking.** A user who also syncs BoursoBank through
Enable Banking will see their current accounts twice, as two distinct `Account`
rows with different `provider` and `external_account_id`. Picsou does not merge
accounts across providers — `V77__merge_duplicate_sync_accounts.sql` only merges
within Enable Banking — so the user soft-deletes one side.

## How it works

```text
BoursoPanel (Sync tab + Add-account modal)
  → /api/bourso/*  → BoursoController → BoursoSyncService ──(executor)──▶ job
                                              │
                                        BoursoAdapter (BoursoPort)
                                              │ HTTP :8001
                                        bourso-auth  (FastAPI + httpx, no browser)
                                              │ HTTPS
                                        clients.boursobank.com + BRS_CONFIG.API_URL
```

**No browser.** BoursoBank's one anti-bot token, `__brs_mit`, is handed out by
the server inside the page body and echoed back as a cookie — there is nothing
to execute. That keeps this sidecar a tenth the size of the Playwright ones.

### Authentication

1. `GET /connexion/` → read `__brs_mit` out of the body, set it plus
   `brsDomainMigration=migrated`, re-request the page.
2. Scrape `form[_token]`, then `GET /connexion/clavier-virtuel?_hinclude=1`.
3. **Decode the virtual keyboard** (see below) and encode the password.
4. `POST /connexion/saisie-mot-de-passe` — a *multipart* form with eight fields.
   Anything but a 302 is a failure; the two known French error strings map to
   `INVALID_CREDENTIALS`.
5. `GET /` — `href="/se-deconnecter"` means signed in, `/securisation` means a
   second factor.

#### The second factor is app-push only

`GET /securisation/validation` must carry `brs-otp-webtoapp`. The sidecar reads
`resourceId` and `formState` out of the HTML-escaped
`data-strong-authentication-payload`, fires
`POST {API_URL}/fr-FR/_user_/_{hash}_/session/challenge/startwebtoapp/{id}` to
send the push, then polls `checkwebtoapp` for up to 120 s exactly as
BoursoBank's own page does, and finally re-submits `/securisation/validation`.

An SMS or e-mail prompt surfaces as `MFA_TYPE_UNSUPPORTED` — a typed, translated
error telling the user to switch to app validation — rather than as a
wrong-password accusation. Only the app path is proven upstream, and a
half-working OTP path that silently burns login attempts is worse than a clear
refusal.

### The virtual keyboard

This is the part a wrong guess makes expensive: BoursoBank counts failed logins
and locks the account, so a pad decoded to the wrong digits does not fail
politely.

Ten buttons each carry a three-letter `data-matrix-key` that rotates per session
**and** an `<img src="data:image/svg+xml;base64,…">`. The digit is only
recoverable from the image — that is the whole point of the design. The ten SVGs
are fixed, so `virtual_pad.py` holds the SHA-256 of each one's base64 payload and
maps it back to its digit; the password is submitted as those key codes joined by
`|`.

It never guesses. A pad that does not yield all ten distinct digits raises rather
than falling back to DOM order, which is shuffled.

### Reading the accounts

`GET /dashboard/liste-comptes?rumroute=dashboard.new_accounts&_hinclude=1`
returns HTML grouped into `data-summary-bank` / `-savings` / `-trading` /
`-loan`. Balances come out of each card's `aria-label` (`Solde : 11 010,00 €`),
negatives use U+2212 rather than an ASCII hyphen.

Securities accounts then get:

```text
GET {API_URL}/_user_/_{hash}_/trading/accounts/summary/{accountId}
    ?_host=tradingboard.boursobank.com&position=ACCOUNTING&responseFormat=true
```

which returns the account's `cash`, `valuation`, `total` and every position, each
money field shaped `{value, decimals, currency}`.

`API_URL` and `USER_HASH` are re-read from the inline `window.BRS_CONFIG` on
every sync rather than persisted, so a session blob can never go stale against
them.

### Fail-closed rules

A partial read must never overwrite a correct portfolio. In order of how likely
each is to bite:

- **Reconciliation**, in the sidecar *and* again in `BoursoSyncService`:
  `total ≈ cash + valuation` and `Σ position.amount ≈ valuation`, tolerance
  `max(0.05 €, 0.1 %)` (Bourse Direct's). A truncated position list still looks
  like a valid, smaller portfolio — this is the check that catches it.
- **Completeness of the dashboard**: every `/compte/…/{32-hex}/` link on the page
  must be accounted for. Counted over the whole page rather than per section on
  purpose — the section regexes stop at the first closing tag, so a card gaining
  a nested list would truncate its section and silently drop everything after it.
- A card that matches but whose id/name/balance/sub-label fails to parse fails
  the **whole sync**, rather than being skipped.
- A position valued in anything but EUR → `INVALID_DATA`. A native `last`
  currency is carried through as `quoteCurrency`, but a cost basis in a foreign
  currency is dropped to null rather than recorded as EUR: it would report a gain
  the size of the FX spread.
- Positions on a cash account, a missing cash balance on a securities account,
  duplicate accounts, an unsupported type → `INVALID_DATA`.

### ISIN resolution is best-effort, on purpose

BoursoBank's trading board exposes only its own instrument symbol (`1rTCW8`), not
an ISIN. The sidecar looks the ISIN up through the public instrument feed and
`OpenFigiIsinConverter` turns it into a Yahoo ticker, exactly as Bourse Direct and
DEGIRO do — but a failure is **not** fatal: the position keeps its BoursoBank
symbol as its ticker.

That is what puts `BoursoBank` in `AccountService.PROVIDER_VALUED`. A line with
no resolvable ticker is unpriceable *by construction* here rather than by
accident, so without the provider-valued fallback a PEA would read as its cash
sleeve alone and the dashboard would book the rest as a loss.

Two positions collapsing onto the same fallback symbol are refused
(`INVALID_DATA`) instead of merging two different instruments into one holding.

### Asynchronous import

Authentication only queues. Manual `POST /api/bourso/sync` returns `202` and the
UI polls `GET /api/bourso/status` while `QUEUED` or `RUNNING`.

```text
IDLE -> QUEUED -> RUNNING -> SUCCESS
                         -> FAILED
```

Jobs carry the session id that created them, so a session cleared or replaced
mid-flight cannot be committed over. Jobs interrupted by a restart are failed at
boot by `BoursoSyncRecovery`. Upstream I/O happens outside any transaction; one
short transaction then replaces every account's holdings and writes the daily
snapshot. The 08:00 scheduler calls `resyncIfSessionActive` through the same gates.

Only `SESSION_EXPIRED` deactivates the session — a transient failure leaves a
usable one so the scheduler simply retries.

## Key files

- `services/bourso-auth/main.py` — FastAPI + httpx sidecar: `/initiate`,
  `/complete`, `/accounts`, `/health`
- `services/bourso-auth/virtual_pad.py` — the SVG→digit table and password encoding
- `services/bourso-auth/accounts_parser.py` — dashboard and trading-board parsing
  plus reconciliation, kept free of FastAPI and httpx so the rules that silently
  break are unit-testable
- `services/bourso-auth/fixtures.py` — the real SVGs and a real captured dashboard
- `backend/.../port/BoursoPort.java`, `BoursoErrorCode.java`
- `backend/.../adapter/BoursoAdapter.java` — 45 s auth / 150 s validation / 90 s accounts
- `backend/.../service/BoursoSyncService.java` — job state machine, validation, atomic persistence
- `backend/.../controller/BoursoController.java` — `/api/bourso/*`
- `backend/.../model/BoursoSession.java`, `BoursoSyncStatus.java`
- `backend/.../config/BoursoSyncConfig.java`, `BoursoSyncRecovery.java`
- `backend/src/main/resources/db/migration/V78__bourso_session.sql`
- `frontend/src/components/sync/BoursoPanel.tsx` (serves the Sync tab and the
  Add-account modal), `frontend/src/pages/sync/BoursoTab.tsx`
- `frontend/src/features/sync/{api,hooks}.ts` — `boursoApi`, `useBourso*`
- i18n namespace `sync.bourso.*` in all four locales

Reuses: `CryptoEncryption`, `AccountService.upsertSnapshot`,
`AccountHolding.providerValueEur` / `providerPnlEur`, `OpenFigiIsinConverter`,
`SyncException` + `GlobalExceptionHandler`, `RateLimitConfig`, `SchedulerService`.

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| `httpx` sidecar, no Playwright | `__brs_mit` is handed out by the server and echoed back; there is no JavaScript challenge to execute | Playwright, like Bourse Direct and Amundi — a browser for nothing |
| Decode the pad by SVG digest | The digit exists only as an image, and the buttons are shuffled per session | Assuming DOM order is 0–9 — what the previous attempt did, and always wrong |
| App push only | It is the only second factor proven upstream | Best-effort SMS/e-mail — untestable, and each failed attempt counts toward a lockout |
| Store cookies only, re-read `BRS_CONFIG` each sync | Nothing persisted can go stale; the session blob stays opaque to Java | Persisting `API_URL`/`USER_HASH` alongside |
| Exclude aggregated third-party accounts | They duplicate Enable Banking with worse freshness | Importing everything and letting the user prune |
| ISIN failure is non-fatal, provider valuation covers it | The trading board has no ISIN; refusing the sync over a label would be absurd | Failing the sync, or dropping the line |
| Reconcile in both sidecar and service | A total that disagrees with its lines means a partial read | Trusting the payload |

See [the ADR](../decisions/2026-08-11-boursobank-httpx-sidecar.md).

## Gotchas / Pitfalls

- **The pad digit is an image, not text.** `<button data-matrix-key="XYZ">` wraps
  an `<img src="data:image/svg+xml;base64,…">`. Do not "simplify" the digest
  lookup back into reading the button's text — there is no text.
- **The served data URI has a space after `base64,`.** Whitespace is stripped
  before hashing so a formatting change does not read as a new image.
- **The challenge token is written by JavaScript**, not carried as an attribute:
  `jQuery("[data-matrix-random-challenge]").val("…")`. The attribute form is kept
  only as a cheap fallback.
- **The login POST is multipart**, not urlencoded, and is only recognised as
  successful by its `302`.
- **Negative balances use U+2212**, not ASCII `-`. Parsing a loan as positive
  would flip a debt into an asset.
- **The section regexes stop at the first closing tag.** That is why the
  completeness check counts account links across the whole page — a savings card
  that gained a nested `<ul>` would truncate its section silently.
- **The trading board is authoritative over the dashboard tile** for a securities
  account: its `total` is the figure the reconciliations ran against.
- **`API_URL` in `BRS_CONFIG` has escaped slashes** (`https:\/\/…`).
- **Single replica.** Pending app-push validations live in the sidecar's process
  memory with a 600 s TTL, exactly as for Bourse Direct and Amundi.
- **`V78` drops and recreates `bourso_session`.** The V23 table held a
  `session_cookies` column that no longer exists, for a connector that never
  shipped enabled. The migration header says so.
- **The error-code CHECK constraint must track `BoursoErrorCode`.** A code
  missing from it turns a diagnosable failure into a 500 at write time.

## Verification boundaries

`services/bourso-auth` — 72 tests, run inside the built image in CI: pad decoding
against the real SVGs (and its refusal on an unknown one), password encoding, the
dashboard parsed from a real captured page including the third-party filter and
the loan exclusion, a card that stops parsing failing the sync, reconciliation
accepted and refused either side of the tolerance, the ISIN-less fallback and its
collision refusal, cookie round-tripping with per-cookie domains, pending TTL, and
the HTTP contract.

Backend — `BoursoAdapterTest` (16), `BoursoSyncServiceTest` (23),
`BoursoControllerTest` (11), `BoursoAdapterWiringTest`, `BoursoSyncRecoveryTest`,
plus the BoursoBank cases added to `AccountServiceTest`,
`AccountConnectionServiceTest` and `IntegrationsServiceTest`.
`SchemaMappingValidationTest` is what proves the entity matches V78.

Frontend — `BoursoTab.test.tsx` (6): the app-push wait, the direct sign-in,
numeric-only credentials, error-code translation, a failed background sync read
from the polled status, and disconnect.

**What is NOT proven.** No public CI runner can hold BoursoBank credentials or
approve a push notification, so the live login has never run. Specifically
unverified:

- the **app-push polling loop** — `checkwebtoapp` being safe to poll is inferred
  from BoursoBank's own page doing it, not observed;
- the **ISIN lookup endpoint** (`_public_/feed/instrument/quote/{symbol}`) — it is
  the one endpoint here with no upstream reference, which is exactly why its
  failure is non-fatal;
- the **`__brs_mit` bootstrap** against a live session.

Everything else — the pad, the account markup, the login form fields, the
trading-board shape — is transcribed from
[azerpas/bourso-api](https://github.com/azerpas/bourso-api), which is maintained
and was current as of August 2026.

A maintainer should perform a live smoke test before release and record the
result here, as the Amundi and DEGIRO notes do.

## Links

- ADR: [2026-08-11 — BoursoBank via a browserless sidecar](../decisions/2026-08-11-boursobank-httpx-sidecar.md)
- Related: [bourse-direct.md](./bourse-direct.md) — the fail-closed discipline this follows
- Related: [degiro-sync.md](./degiro-sync.md) — the other `httpx`-only sidecar
- Related: [encryption-at-rest.md](./encryption-at-rest.md)
- Upstream reference: [azerpas/bourso-api](https://github.com/azerpas/bourso-api)
