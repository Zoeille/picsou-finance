# Feature: Frontend utility library (`lib/utils.ts`)

> Last updated: 2026-05-31 (`parseDate` + hybrid `DateInput`)

## Context

Shared formatting functions used across the frontend. Centralised in one file to ensure consistent number/date formatting and avoid ad-hoc `Intl` calls scattered across components. The locale now defaults to the active app language through `document.documentElement.lang`, which the i18n bootstrap keeps in sync, instead of hardcoded `fr-FR`.

## How it works

### Key files

- `frontend/src/lib/utils.ts` — all helpers
- `frontend/src/lib/utils.test.ts` — Vitest unit tests

### API surface

| Function | Signature | Output example |
|----------|-----------|---------------|
| `cn` | `(...inputs: ClassValue[]) => string` | Merges Tailwind classes via clsx + tailwind-merge |
| `getLocale` | `() => string` | `'fr-FR'` or `'en-US'` based on `document.documentElement.lang` |
| `localeFromLanguage` | `(language) => string` | Maps i18n/browser language codes to `'fr-FR'` / `'en-US'` |
| `formatCurrency` | `(value, currency='EUR', locale=getLocale())` | `"1 234,50 €"`; falls back to decimal + code for invalid currency values |
| `formatDate` | `(dateStr, locale=getLocale(), format?)` | `"08/04/2026"` (locale) or `"08-04-2026"` (iso) |
| `parseDate` | `(input, locale=getLocale(), format=store.dateFormat) => string \| null` | `"08/04/2026"` → `"2026-04-08"` (inverse of `formatDate`); `null` if unparseable |
| `formatDateTime` | `(dateStr, locale=getLocale(), format?)` | `"08/04/2026 14:30"` (locale) or `"08-04-2026 14:30"` (iso) |
| `normalizeDecimal` | `(value: string \| null \| undefined) => string` | `"12,50"` → `"12.50"` (replaces first `,` with `.`) |
| `parseAmount` | `(value: string \| null \| undefined) => number` | `"12,50"` → `12.5`; tolerant `parseFloat` over `normalizeDecimal` |
| `formatLocalDate` | `(dateStr, locale=getLocale())` | `"8 avril 2026"` (long month) |
| `formatPercent` | `(value, locale=getLocale())` | `"50,0 %"` — value is a ratio (0.5 → 50%) |
| `formatTimeAgo` | `(dateStr, locale=getLocale())` | `"il y a 3 heures"` via `Intl.RelativeTimeFormat` |
| `todayLabel` | `(locale=getLocale(), date=new Date())` | `"Mardi 8 avril 2026"` (weekday + full date, sentence-cased) |
| `accountTypeLabel` | `(type: string)` | `"Compte courant"`, `"PEA"`, etc. |
| `safeRedirect` | `(redirect, fallback='/')` | Returns the path only if it starts with `/`, else fallback |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `Intl.NumberFormat` / `Intl.DateTimeFormat` for all formatting | Native, locale-aware, no extra dependency | date-fns / numeral.js |
| `Intl.RelativeTimeFormat` for `formatTimeAgo` | Locale-correct relative strings (fr/en) | Manual string building per locale |
| `formatDate` reads `dateFormat` from Zustand store | User can toggle between locale-aware and fixed `DD-MM-YYYY` in settings | Hardcoded format — no user preference |
| `formatPercent` takes a ratio (0–1) | Matches `Intl.NumberFormat` `style: 'percent'` convention | Percent value (0–100) — inconsistent with Intl |
| `parseAmount` instead of bare `parseFloat` everywhere | French users type `12,50`; native `type="number"` inputs reject commas in FR locales and `parseFloat("12,50")` → `12`. One chokepoint fixes all amount entry | Per-field `.replace(',', '.')` (easy to forget on new fields) |

### Decimal input — `NumericInput`

`frontend/src/components/shared/NumericInput.tsx` is the shared amount-entry component. It wraps the shadcn `Input` as `type="text" inputMode="decimal"` (mobile numeric keypad, never rejects a comma) and sanitizes keystrokes to digits + a single `.`/`,` separator + an optional leading `-`. It rewrites `e.target.value` **before** calling the passed `onChange`, so it works with:

- **controlled-string forms** — `value`/`onChange` reading `e.target.value`, then `parseAmount(...)` at submit;
- **react-hook-form** — `register(name, { setValueAs: v => parseAmount(v) })` (RHF also reads the sanitized `e.target.value`). Relies on React 19 treating `ref` as a regular prop, so no `forwardRef` is needed.

All numeric inputs across Picsou (account balances & loan fields, goal target, month override/manual contribution, transaction qty/price/amount, holding qty/buy-in, month-end balances) use `NumericInput` + `parseAmount`.

### Page header date

`frontend/src/components/shared/PageHeader.tsx` uses `todayLabel(locale, headerDate)` as its default surtitle so top-level pages keep the same dated header rhythm. The header stores the date once at mount, then formats it from the active i18n language (`fr-FR` / `en-US`) so changing the app language updates the visible weekday and month names without needing a page reload. Pass an explicit `surtitle` only when a page needs a different label.

`frontend/src/i18n/index.ts` also synchronizes `<html lang>` after init and on
every language change, so lower-level helpers and charts that call `getLocale()`
inherit the same language as the visible app.

`todayLabel` sentence-cases the first character after `Intl.DateTimeFormat`
returns the localized string, so French headers show `Lundi 6 juillet 2026`
rather than `lundi 6 juillet 2026`.

### Date input — `DateInput` (hybrid native/desktop)

`frontend/src/components/shared/DateInput.tsx` is the shared date-entry component.
Its external contract is **always an ISO `yyyy-MM-dd` string** (`{ value, onChange:
(iso) => void, id?, required?, disabled?, className? }`), regardless of how it's
displayed. It exists because a native `<input type="date">` always renders in the
OS/browser locale and **cannot** be coerced to honour the in-app `dateFormat`
setting (`dd/mm/yyyy` vs `dd-mm-yyyy`):

- **Touch devices** (`useIsTouchDevice()` → `matchMedia('(pointer: coarse)')`,
  `frontend/src/hooks/use-touch-device.ts`) render the native `<input type="date">`
  — the OS picker is the best mobile experience and already speaks ISO.
- **Desktop** renders a text field that *displays* `formatDate(value)` and *parses*
  typed text back to ISO with `parseDate`. It only emits `onChange` once the text
  parses to a real date (or `''` when cleared), so a half-typed value never
  propagates a garbage ISO upstream. A `common.dateHint` placeholder shows the
  expected shape, and `aria-invalid` flags an unparseable value after blur.

It resyncs the visible text when the external `value` or the `dateFormat` setting
changes, derived **during render** (tracking `lastValue`/`lastFormat` in state) to
avoid a cascading re-render — the same "derive during render" pattern used in
`ConfirmDialog`.

Wired into the four date fields: `AddTransactionModal` (transaction date),
`GoalsPage` (deadline), and `AccountForm` (loan start/end — via react-hook-form
`<Controller>` so the ISO value flows through `value`/`onChange`).

## Gotchas / Pitfalls

- **`getLocale()` reads `document.documentElement.lang`** — this is set by the `<html lang>` attribute. For UI that must react immediately to language changes, pass the active i18n language explicitly instead of relying on the document fallback.
- **`formatCurrency()` validates its locale before calling `Intl.NumberFormat`** — invalid translation/mocking values must not crash the UI. Invalid currency codes fall back to a decimal number followed by the raw code.
- **`formatDate` vs `formatLocalDate`**: `formatDate` outputs `dd/mm/yyyy` (compact, for tables) or `DD-MM-YYYY` if the user selected the ISO format in settings; `formatLocalDate` outputs long-month form (for readable labels). Don't swap them.
- **`formatDate` format resolution**: reads `useAppStore.getState().dateFormat` at call time (`'locale'` or `'iso'`). The optional `format` parameter overrides the store value — used by callers that need a specific format regardless of user preference.
- **Store import in `utils.ts`**: `formatDate` imports `useAppStore` directly — safe because `app-store.ts` has no dependency on `utils.ts` (no circular dependency).
- **`formatPercent` expects a ratio** (0.5 = 50%), not a percentage value. Passing `50` instead of `0.5` will output `"5 000 %"`.
- **`formatDate` uses `new Date(dateStr)`** — ISO datetime strings work fine; bare date strings like `"2026-04-08"` may shift by timezone offset. Use `formatLocalDate` for LocalDate (date-only) values from the backend to avoid off-by-one-day issues.
- **`parseDate` is the strict inverse of `formatDate`** — the year is the last token in every shape we render (`dd-mm-yyyy`, `dd/mm/yyyy`, `mm/dd/yyyy`); only day/month order varies (`mm/dd` for **en-US in non-iso mode**, `dd/mm` otherwise). It accepts `/`, `-`, `.` separators interchangeably, expands 2-digit years to the 2000s, and round-trips impossible dates (e.g. `31/02`) to `null` by re-checking via `new Date`. When changing `formatDate`'s output shape, update `parseDate` and the round-trip test together.
- **`DateInput` desktop branch never emits an invalid ISO** — `onChange` fires only when `parseDate` succeeds (or `''` on clear). Consumers therefore can't rely on `onChange` firing for every keystroke; the displayed text is internal state until it parses.
- **`safeRedirect` is a security guard** — always use it before redirecting to a URL from query params to prevent open redirect attacks.
- **`accountTypeLabel` returns the raw type if unknown** — if new `AccountType` values are added to the backend, add them here to avoid displaying enum keys in the UI.

## Tests

- `frontend/src/lib/utils.test.ts` — covers `cn`, `formatCurrency` including invalid currency/locale fallback, `formatDate`, `formatPercent`, and `parseDate` (dd/mm vs mm/dd ordering, iso mode, mixed separators, 2-digit years, impossible-date rejection, malformed input, and the `formatDate`∘`parseDate` round-trip across both formats × both locales).
