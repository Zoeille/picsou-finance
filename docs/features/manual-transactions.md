# Feature: Manual Transactions

> Last updated: 2026-07-08

## Context

Picsou supports syncing transactions from external sources (Finary, bank connectors, Trade Republic, crypto exchanges). This feature adds the ability to manually record any transaction — including on accounts that are also synced — and derives balance/holdings from those transactions automatically.

## How it works

### DB schema (V24 migration)

Five new columns on the `transaction` table (all nullable or defaulted, backward-compatible):

```sql
ALTER TABLE transaction
  ADD COLUMN is_manual      BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN tx_type        VARCHAR(20)   NULL,  -- DEPOSIT | WITHDRAWAL | BUY | SELL | DIVIDEND | FEE
  ADD COLUMN ticker         VARCHAR(30)   NULL,
  ADD COLUMN quantity       NUMERIC(20,8) NULL,
  ADD COLUMN price_per_unit NUMERIC(20,8) NULL;

CREATE INDEX idx_transaction_account_manual ON transaction(account_id, is_manual);
```

Existing synced transactions have `is_manual = false`. The original `type` column (Finary raw category string) is untouched.

### Per-trade fees column (V53 migration)

```sql
ALTER TABLE transaction ADD COLUMN fees NUMERIC(20,8) NULL;  -- null = zero downstream
```

Optional broker/transaction fees on a BUY/SELL. Fees **fold into the PMP cost basis** (French PEA
convention: acquisition costs raise the cost basis) — see *Holdings derivation* below. The signed
`amount` also accounts for fees: BUY `= −(qty·price + fees)`, SELL `= +(qty·price − fees)`,
centralised in `TransactionAmountCalculator` and mirrored by the frontend modal. Added for the CSV
importer ([csv-transaction-import.md](csv-transaction-import.md)) and exposed as an optional field on
the manual add/edit form.

### Security name column (V36 migration)

A later migration adds a first-class label for derived positions:

```sql
ALTER TABLE transaction
  ADD COLUMN name VARCHAR(100) NULL;
```

`transaction.name` is the human-readable security name, distinct from the row `description`. It is auto-filled from OpenFIGI when an ISIN is entered, or from the user-supplied "Nom". `HoldingComputeService` uses it to label each position (see below). The width matches `account_holding.name` so the value copies across without truncation.

### Transaction types

`TransactionType` enum: `DEPOSIT`, `WITHDRAWAL`, `BUY`, `SELL`, `DIVIDEND`, `FEE`.

- Cash accounts (CHECKING, SAVINGS, LEP, OTHER): use DEPOSIT / WITHDRAWAL. The `amount` field is signed (positive = deposit, negative = withdrawal).
- Investment accounts (PEA, COMPTE_TITRES, CRYPTO): use BUY / SELL. The `amount` is signed (negative for BUY, positive for SELL, reflecting cash flow).

### Entering a position by ISIN

On an investment account, the instrument field accepts **either** a Yahoo ticker (e.g. `IWDA.AS`) **or** a 12-character ISIN (e.g. `IE00B4L5Y983`). `ManualTransactionService.applyInstrumentFields()` detects an ISIN via `OpenFigiIsinConverter.isIsin()` and, when matched, calls `resolve()` to convert it to a Yahoo ticker + display name. Resolution happens at **write time**, so:

- an ISIN entry and the equivalent ticker entry merge into a **single position** (the grouping key is the resolved ticker, not the raw input — this is what kills duplicate positions);
- Yahoo pricing works (`YahooFinancePriceProvider` rejects raw ISINs);
- the raw ISIN never surfaces in the transaction row — the service owns the `description` for BUY/SELL (see Gotchas).

A user-supplied "Nom" always wins over the resolved name. The same logic runs for both add and edit (`addTransaction` and `updateTransaction` share `applyInstrumentFields`).

### Balance derivation (cash accounts)

When a manual transaction is added, edited, or deleted on a **manual** cash account (`account.isManual = true`), `ManualTransactionService` recomputes `account.currentBalance` as the sum of all transaction amounts via a single aggregate query (`sumAmountByAccountId`). It then calls `FinaryPersistenceHelper.reconstructSnapshotsFromDb()` to rebuild the balance history from scratch.

### Synced accounts

Manual transactions on a **synced** cash account (`account.isManual = false` — bank-synced via Enable Banking, Trade Republic, wallet, or exchange accounts) are recorded but never drive the account's balance or snapshot history. Only `isManual` accounts get transaction-derived balances; for synced accounts the balance and snapshots are owned by the provider sync, and rebuilding them from the (usually sparse) manual transaction list would overwrite the balance and delete the provider-written snapshot history. Finary-created accounts have `isManual = true`, so they keep the transaction-derived path. Investment accounts are unaffected: `recomputeHoldings` runs after every manual BUY/SELL regardless of account provenance. The MCP `add_transaction` tool goes through the same service, so it inherits the rule.

### Holdings derivation (investment accounts)

`HoldingComputeService.recomputeHoldings(account)` is called after every manual transaction add/delete on a PEA, COMPTE_TITRES, or CRYPTO account:

1. Fetches all BUY/SELL transactions for the account, ordered by date ASC.
2. Groups by ticker: `net quantity = Σ(BUY qty) − Σ(SELL qty)`.
3. Computes `averageBuyIn` as VWAP across all BUY transactions for each ticker, **fees included**: `Σ(qty·price + fees) / Σ(qty)` (null fees treated as zero). SELL rows never affect the average.
4. Upserts `AccountHolding` for tickers where `qty > 0`.
5. Deletes `AccountHolding` for tickers where `qty ≤ 0`.
6. Labels each surviving position with the **most recent** transaction (date ASC, last write wins) that carries a non-blank `name`. The update is guarded by a null check, so a nameless manual transaction never erases a name already set by bank sync (OpenFIGI).

All existing holdings are loaded upfront (one query) to avoid N+1 lookups.

### Manual transactions survive re-syncs

All sync services (`FinaryPersistenceHelper`, `BoursoSyncService`) now call `transactionRepository.deleteByAccountIdAndIsManualFalse(accountId)` instead of `deleteByAccountId(accountId)`. Manual transactions are preserved across any sync.

### REST endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/accounts/{id}/transactions` | Add a manual transaction (returns 201) |
| `PUT` | `/api/accounts/{id}/transactions/{txId}` | Edit a manual transaction |
| `DELETE` | `/api/accounts/{id}/transactions/{txId}` | Delete a manual transaction (returns 204) |

`DELETE` validates that the transaction is manual (`isManual = true`). Synced transactions cannot be deleted via this endpoint.

### Frontend

`AddTransactionModal` is account-type-aware:

**Cash accounts (CHECKING, SAVINGS, LEP, OTHER):**
- Date, DEPOSIT/WITHDRAWAL toggle, Description, Amount (always positive — toggle sets sign)

**Investment accounts (PEA, COMPTE_TITRES, CRYPTO):**
- Date, BUY/SELL toggle, **Ticker ou ISIN** (a ticker like `IWDA.AS` or a 12-char ISIN like `IE00B4L5Y983`), Name (auto-filled from existing holdings when the ticker matches; otherwise resolved from the ISIN backend-side), Quantity, Price per unit, **Fees (optional, folded into the PMP)**, Total (read-only)

The Transactions list shows a "Manuel" badge on manual entries and a delete button (only for manual entries).

After submit, `useAddTransaction` / `useDeleteTransaction` hooks invalidate the `transactions`, `history`, `account`, and `dashboard` queries.

### Key files

| File | Role |
|------|------|
| `db/migration/V24__manual_transactions.sql` | Schema extension (is_manual, tx_type, ticker, quantity, price_per_unit) |
| `db/migration/V36__transaction_security_name.sql` | Adds `transaction.name` (position label) |
| `model/TransactionType.java` | Enum (DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE) |
| `service/HoldingComputeService.java` | Derives holdings (qty, VWAP, **name**) from BUY/SELL transactions |
| `service/ManualTransactionService.java` | Orchestrates add/edit/delete + re-derivation; persists `fees`; delegates ISIN/ticker/description to `InstrumentFieldResolver` |
| `service/InstrumentFieldResolver.java` | Shared ISIN→ticker/name + BUY/SELL description builder (reused by the CSV importer) |
| `imports/TransactionAmountCalculator.java` | Single source of truth for the signed `amount` incl. fees |
| `db/migration/V53__transaction_fees.sql` | Adds `transaction.fees` (folds into the PMP) |
| `adapter/OpenFigiIsinConverter.java` | `isIsin()` detection + `resolve()` ISIN→ticker+name (shared with bank sync) |
| `controller/AccountController.java` | POST/DELETE `/accounts/{id}/transactions` |
| `repository/TransactionRepository.java` | `deleteByAccountIdAndIsManualFalse`, `sumAmountByAccountId`, `findByAccountIdAndTxTypeInOrderByDateAsc` |
| `frontend/src/components/shared/AddTransactionModal.tsx` | Account-type-aware form modal |
| `frontend/src/components/shared/TransactionsList.tsx` | Manuel badge + delete button |
| `frontend/src/features/accounts/hooks.ts` | `useAddTransaction`, `useDeleteTransaction` |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Derive balance from transactions | Keeps single source of truth; avoids divergence between manual entries and computed balance | Letting users set balance directly (leads to inconsistency with transactions) |
| `is_manual` flag on transaction | Minimal schema change; syncs can cleanly skip manual rows | Separate `manual_transaction` table (more joins, more complex) |
| Delete-synced-rows-except-manual on re-sync | Manual data survives any number of re-syncs | Timestamped merge (more complex, edge cases with overlapping date ranges) |
| VWAP for averageBuyIn | Standard financial convention for cost basis | FIFO (more complex, requires ordered lot tracking) |

## Gotchas / Pitfalls

- **Investment account balance is NOT recomputed** from manual transactions. Only the holdings (positions) are derived. The account's `currentBalance` is set by the price scheduler (qty × live price). This is intentional for investment accounts.
- **Synced transactions cannot be deleted**: The DELETE endpoint checks `isManual`. Attempting to delete a synced transaction returns 403.
- **Holdings recomputation is full**: Every add/delete triggers a full re-derivation for that account (all tickers). This is fast in practice since investment accounts rarely have hundreds of tickers.
- **The backend owns the investment description**: For BUY/SELL, `ManualTransactionService` sets the row `description` from the effective name, or `Achat {TICKER}` / `Vente {TICKER}` when no name exists — overriding whatever the client sent. This is what stops a raw ISIN (entered in the Ticker field with a blank Nom) from leaking into the transaction row. Cash transactions keep the client-supplied description.

## Tests

- `HoldingComputeServiceTest` — 11 unit tests: BUY-only, multi-BUY VWAP, BUY+SELL, fully-sold position, null ticker/quantity skipping, multiple tickers, existing holding update, plus position name = newest transaction's name and name-preserved-when-transactions-have-none.
- `ManualTransactionServiceTest` — 11 unit tests: manual cash add (balance + snapshots recomputed), synced cash add (transaction saved, balance/snapshots untouched), investment add (holdings recomputed, for both manual and synced accounts), non-owned account rejection, manual delete, synced-account delete (no reconstruct), synced-transaction delete rejection, not-found rejection, plus ISIN input → resolved ticker/name/description and plain-ticker uppercased with the user "Nom" winning.
- `OpenFigiIsinConverterTest` — 4 unit tests for the `isIsin()` detector: valid ISINs, case/whitespace normalization, rejects tickers/non-ISIN strings, rejects null/blank.
