# Feature: CSV transaction import (investment accounts)

> Last updated: 2026-07-11

## Context

Users want to seed a PEA/CTO (or crypto) account with their broker's trade history without
re-keying every line ([issue #38](https://github.com/Zoeille/picsou-finance/issues/38)). This
adds a two-phase, per-account CSV importer that accepts any broker layout — the user maps the
columns — and writes manual BUY/SELL transactions, folding per-trade fees into the cost basis.

## How it works

Two phases, modelled on the Finary XLSX importer but mapping **columns** into an
**already-selected account** (not mapping accounts):

1. **Preview** — the raw file is uploaded, the dialect (delimiter / decimal / date format) is
   sniffed, a best-guess column mapping is built from the header names, and the raw file is cached
   under a `fileToken` **bound to the target account** (30-min TTL). The response returns the
   detected columns, a sample of rows, and the guesses — all overridable in the wizard.
2. **Execute** — the client echoes the `fileToken` plus the (possibly user-adjusted) mapping and
   dialect. The raw file is **re-parsed** with the confirmed dialect, each row is mapped to a
   manual BUY/SELL transaction, valid rows are bulk-inserted (`is_manual = true`), and holdings are
   recomputed **once**. Invalid rows are reported per-row rather than failing the whole file.

### Key files

- `backend/src/main/java/com/picsou/imports/csv/` — `CsvReader` (RFC-4180, configurable delimiter),
  `CsvDialectDetector` (delimiter/decimal/date sniffing), `CsvValueParser` (`BigDecimal`/`LocalDate`
  parsing), `CsvDialect` + `DecimalStyle`. Dependency-free; symmetric with the GDPR `CsvWriter`.
- `backend/src/main/java/com/picsou/imports/TransactionRowMapper.java` — one row → unsaved
  `Transaction`; reuses `InstrumentFieldResolver` (ISIN→ticker) and `TransactionAmountCalculator`
  (signed amount incl. fees).
- `backend/src/main/java/com/picsou/service/TransactionImportService.java` — preview/execute + the
  `fileToken` cache and its `@Scheduled` TTL sweep.
- `backend/src/main/java/com/picsou/controller/TransactionImportController.java` —
  `POST /api/accounts/{id}/transactions/import/preview` (multipart) and
  `POST /api/accounts/{id}/transactions/import` (JSON, `201`). Member-scoped + `syncBuckets` throttle.
- `frontend/src/components/shared/ImportTransactionsModal.tsx` — the 3-step wizard.
- `frontend/src/features/accounts/{api,hooks}.ts` — `importPreview` / `importExecute` + hooks.

### Flow

```
upload CSV ─► preview() ─► detect dialect + guess mapping ─► cache raw file @token(account)
                                                                     │
user adjusts mapping/dialect ◄───────────────────────────────────────┘
        │
        ▼
execute(token, mapping, dialect) ─► re-parse ─► map rows ─► saveAll(is_manual) ─► recomputeHoldings()
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Hand-rolled `CsvReader` (no lib) | Matches the existing hand-rolled `CsvWriter`; the hard part (delimiter/decimal/date sniffing) isn't solved by a lib anyway | opencsv / Commons-CSV |
| Column-mapping wizard | Works for any broker export without a per-broker parser | Fixed template · per-broker native parsers |
| Cache the **raw file**, re-parse on execute | The user can change the delimiter after preview; caching parsed rows would be stale | Cache parsed rows |
| Token bound to the account | A preview cannot be replayed against another account | Bare token |
| Tolerant per-row errors | One bad line shouldn't sink a multi-year import | All-or-nothing transaction |

## Gotchas / Pitfalls

- **French broker exports** commonly use `;` delimiter and `,` decimals (`1 234,56`). The detector
  handles both; the user can override delimiter, decimal style, and date format in the wizard.
- **ISINs must be resolved to tickers at import time** (`InstrumentFieldResolver`) or Yahoo pricing
  never populates and an ISIN row won't merge with the equivalent ticker row.
- **Amount signing lives in one place** (`TransactionAmountCalculator`, mirrored in the TS modal):
  BUY `= −(qty·price + fees)`, SELL `= +(qty·price − fees)`. Fees also fold into the PMP — see
  [manual-transactions.md](manual-transactions.md).
- The importer only accepts **investment** accounts (PEA / COMPTE_TITRES / CRYPTO); anything else → 400.
- Multipart limit was raised to **10 MB** (`application.yml`) for multi-year histories; the endpoint
  is member-scoped and throttled.
- Demo mode returns `{}` for unhandled endpoints — UI consumers must guard accordingly.

## Tests

- `CsvReaderTest`, `CsvDialectDetectorTest`, `CsvValueParserTest` — parsing / sniffing.
- `TransactionRowMapperTest` — sign+fees, ISIN resolution, amount-derived price, bad rows.
- `TransactionImportServiceTest` — happy path, expired token, **token↔account binding**,
  non-investment (400), foreign account (404), per-row error reporting.
- `ImportTransactionsModal.test.tsx` — preview → mapping → import request → result.

## Links

- Sibling importer: [finary-import.md](finary-import.md) · [trade-republic.md](trade-republic.md)
- [manual-transactions.md](manual-transactions.md) · [ISIN_TO_TICKER_CONVERSION.md](ISIN_TO_TICKER_CONVERSION.md)
- Ticket: [issue #38](https://github.com/Zoeille/picsou-finance/issues/38)
