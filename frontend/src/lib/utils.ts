import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"
import { useAppStore, type DateFormat } from "@/stores/app-store"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Accept French commas as decimal separators: "12,50" → "12.50". */
export function normalizeDecimal(value: string | null | undefined): string {
  return (value ?? '').replace(',', '.')
}

/** Parse a user-entered amount tolerating both "." and "," separators. */
export function parseAmount(value: string | null | undefined): number {
  return parseFloat(normalizeDecimal(value))
}

export function getLocale(): string {
  try {
    return localeFromLanguage(document.documentElement.lang || navigator.language)
  } catch {
    return 'fr-FR'
  }
}

export function localeFromLanguage(language: string | null | undefined): string {
  return language?.startsWith('fr') ? 'fr-FR' : 'en-US'
}

function normalizeIntlLocale(locale: string): string {
  try {
    return Intl.NumberFormat.supportedLocalesOf(locale).length > 0 ? locale : 'fr-FR'
  } catch {
    return 'fr-FR'
  }
}

export function formatCurrency(value: number, currency = 'EUR', locale = getLocale()): string {
  const safeLocale = normalizeIntlLocale(locale)
  try {
    return new Intl.NumberFormat(safeLocale, { style: 'currency', currency }).format(value)
  } catch {
    // An unknown/invalid ISO 4217 code makes Intl.NumberFormat throw a RangeError.
    // Degrade to a plain decimal + the raw code instead of crashing the whole app (issue #9).
    const num = new Intl.NumberFormat(safeLocale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)
    return `${num} ${currency}`
  }
}

export function formatDate(dateStr: string | null | undefined, locale = getLocale(), format?: DateFormat): string {
  if (!dateStr) return '—'
  const resolvedFormat = format ?? useAppStore.getState().dateFormat
  if (resolvedFormat === 'iso') {
    const d = new Date(dateStr)
    const day = String(d.getDate()).padStart(2, '0')
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const year = d.getFullYear()
    return `${day}-${month}-${year}`
  }
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(dateStr))
}

/**
 * Inverse of {@link formatDate}: parses a user-typed date string back into an
 * ISO `yyyy-MM-dd` string, honoring the active format/locale, or returns `null`
 * when the input can't be parsed into a real calendar date.
 *
 * Accepts `/`, `-` and `.` as separators regardless of the active format (people
 * mix them), and tolerates 2-digit years. The year is always the last token in
 * every shape we render (`dd-mm-yyyy`, `dd/mm/yyyy`, `mm/dd/yyyy`); only the
 * day/month order varies — `mm/dd` for en-US locale (non-iso), `dd/mm` otherwise.
 */
export function parseDate(
  input: string | null | undefined,
  locale = getLocale(),
  format: DateFormat = useAppStore.getState().dateFormat,
): string | null {
  if (!input) return null
  const parts = input.trim().split(/[/.-]/).map((p) => p.trim())
  if (parts.length !== 3 || parts.some((p) => !/^\d+$/.test(p))) return null

  const monthFirst = format !== 'iso' && locale.startsWith('en')
  const [first, second, yearStr] = parts
  const day = Number(monthFirst ? second : first)
  const month = Number(monthFirst ? first : second)
  let year = Number(yearStr)
  if (yearStr.length === 2) year += 2000

  if (year < 1000 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) return null

  const iso = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  // Reject impossible dates (e.g. 31/02) by round-tripping through Date.
  const d = new Date(`${iso}T00:00:00`)
  if (d.getFullYear() !== year || d.getMonth() + 1 !== month || d.getDate() !== day) return null
  return iso
}

export function formatDateTime(dateStr: string | null | undefined, locale = getLocale(), format?: DateFormat): string {
  if (!dateStr) return '—'
  const d = new Date(dateStr)
  const resolvedFormat = format ?? useAppStore.getState().dateFormat
  if (resolvedFormat === 'iso') {
    const day = String(d.getDate()).padStart(2, '0')
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const year = d.getFullYear()
    const hours = String(d.getHours()).padStart(2, '0')
    const minutes = String(d.getMinutes()).padStart(2, '0')
    return `${day}-${month}-${year} ${hours}:${minutes}`
  }
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(d)
}

export function formatPercent(value: number, locale = getLocale()): string {
  return new Intl.NumberFormat(locale, { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(value)
}

function capitalizeFirstCharacter(value: string, locale = getLocale()): string {
  const [first = '', ...rest] = Array.from(value)
  return first.toLocaleUpperCase(normalizeIntlLocale(locale)) + rest.join('')
}

export function todayLabel(locale = getLocale(), date = new Date()): string {
  const safeLocale = normalizeIntlLocale(locale)
  const label = new Intl.DateTimeFormat(safeLocale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(date)
  return capitalizeFirstCharacter(label, safeLocale)
}

export function formatLocalDate(dateStr: string | null | undefined, locale = getLocale()): string {
  if (!dateStr) return '—'
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'long', year: 'numeric' }).format(new Date(dateStr))
}

export function formatTimeAgo(dateStr: string | null | undefined, locale = getLocale()): string {
  if (!dateStr) return '—'
  const diff = Date.now() - new Date(dateStr).getTime()
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 1) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(0, 'minute')
  if (minutes < 60) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-minutes, 'minute')
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-hours, 'hour')
  const days = Math.floor(hours / 24)
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-days, 'day')
}

export function accountTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    LEP: 'LEP',
    PEA: 'PEA',
    COMPTE_TITRES: 'Compte-titres',
    CRYPTO: 'Crypto',
    CHECKING: 'Compte courant',
    SAVINGS: 'Épargne',
    REAL_ESTATE: 'Immobilier',
    LOAN: 'Emprunt',
    OTHER: 'Autre',
  }
  return labels[type] ?? type
}

export function safeRedirect(redirect: string | null, fallback = '/'): string {
  if (!redirect || !redirect.startsWith('/')) return fallback
  return redirect
}
