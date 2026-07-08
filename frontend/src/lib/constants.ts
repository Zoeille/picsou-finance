import type { AccountType } from '@/types/api'

export const ACCOUNT_TYPES: { value: AccountType; labelKey: string }[] = [
  { value: 'CHECKING', labelKey: 'accountTypes.checking' },
  { value: 'SAVINGS', labelKey: 'accountTypes.savings' },
  { value: 'LEP', labelKey: 'accountTypes.lep' },
  { value: 'PEA', labelKey: 'accountTypes.pea' },
  { value: 'COMPTE_TITRES', labelKey: 'accountTypes.compteTitres' },
  { value: 'CRYPTO', labelKey: 'accountTypes.crypto' },
  { value: 'REAL_ESTATE', labelKey: 'accountTypes.realEstate' },
  { value: 'LOAN', labelKey: 'accountTypes.loan' },
  { value: 'OTHER', labelKey: 'accountTypes.other' },
]

/** Translation key for an account type's display label. */
export function accountTypeLabelKey(type: AccountType): string {
  return ACCOUNT_TYPES.find((t) => t.value === type)?.labelKey ?? 'accountTypes.other'
}

/**
 * Curated list of valid ISO 4217 codes offered in the account form's currency
 * dropdown (EUR first). Labels are rendered live via `Intl.DisplayNames`, so this
 * stays codes-only and is trivial to extend. The backend `@ValidCurrency` constraint
 * accepts any real ISO 4217 code, so this list can grow without backend changes.
 */
export const SUPPORTED_CURRENCIES = [
  'EUR', 'USD', 'GBP', 'CHF', 'JPY', 'CAD', 'AUD', 'CNY',
  'SEK', 'NOK', 'DKK', 'NZD', 'HKD', 'SGD', 'PLN',
] as const

export const ACCOUNT_COLORS = [
  '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#f43f5e', '#ef4444', '#f97316',
  '#eab308', '#84cc16', '#22c55e', '#10b981',
  '#14b8a6', '#06b6d4', '#0ea5e9', '#3b82f6',
]

export const QUERY_STALE_TIMES = {
  dashboard: 5 * 60 * 1000,
  accounts: 1 * 60 * 1000,
  accountDetail: 2 * 60 * 1000,
  sync: 30 * 1000,
  goals: 2 * 60 * 1000,
} as const

/**
 * Length of the SMS verification code (TAN) Trade Republic sends during device
 * pairing. Shared by every TR entry point (AddAccountModal, SyncAllModal,
 * TradeRepublicTab) so client-side validation stays consistent.
 */
export const TR_VERIFICATION_CODE_LENGTH = 4

/**
 * How long a successful `session-probe` result (RequireAuth's cookie-backed
 * session check) may sit in the query cache after it stops being observed
 * (isAuthenticated flips true). Bounded rather than Infinity so a stale
 * "success" can eventually be garbage-collected as a backstop, even if some
 * future logout path forgot to explicitly clear it via queryClient.clear().
 */
export const SESSION_PROBE_GC_TIME = 5 * 60 * 1000
