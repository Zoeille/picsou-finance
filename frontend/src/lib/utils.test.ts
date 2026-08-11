import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { cn, formatCurrency, formatDate, formatPercent, freshnessLevel, localeFromLanguage, parseDate, todayLabel } from './utils'

describe('cn', () => {
  it('merges class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('handles conditional classes', () => {
    const enabled = false
    expect(cn('foo', enabled && 'bar', 'baz')).toBe('foo baz')
  })

  it('merges tailwind conflicts', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })
})

describe('formatCurrency', () => {
  it('formats EUR in French locale', () => {
    const result = formatCurrency(1234.5, 'EUR', 'fr-FR')
    expect(result).toContain('1')
    expect(result).toContain('234')
  })

  it('formats zero', () => {
    const result = formatCurrency(0, 'EUR', 'fr-FR')
    expect(result).toContain('0')
  })

  it('formats negative values', () => {
    const result = formatCurrency(-500, 'EUR', 'fr-FR')
    expect(result).toContain('500')
  })

  it('degrades gracefully on an invalid currency code instead of throwing (issue #9)', () => {
    expect(() => formatCurrency(100, 'AMAT', 'fr-FR')).not.toThrow()
    const result = formatCurrency(100, 'AMAT', 'fr-FR')
    expect(result).toContain('AMAT')
    expect(result).toContain('100')
  })

  it('degrades gracefully when both currency and locale are invalid', () => {
    expect(() => formatCurrency(100, 'AMAT', 'common.locale')).not.toThrow()
    const result = formatCurrency(100, 'AMAT', 'common.locale')
    expect(result).toContain('AMAT')
    expect(result).toContain('100')
  })
})

describe('formatDate', () => {
  it('formats ISO date string', () => {
    const result = formatDate('2025-03-15', 'fr-FR')
    expect(result).toBeTruthy()
    expect(typeof result).toBe('string')
  })

  describe('west of UTC', () => {
    // A date-only value denotes a calendar day, not an instant: `new Date('2026-07-31')` is
    // specified to parse as UTC midnight, so behind UTC every LocalDate the backend sends —
    // priceAsOf, transaction dates, goal deadlines — used to render as the day before. The
    // runner's own zone is UTC, where the bug is invisible, hence forcing one here.
    // stubEnv rather than touching process.env: it is typed by vitest, so the app tsconfig keeps
    // its node-free `types: ["vite/client"]`, and the restore is handled for us.
    beforeEach(() => { vi.stubEnv('TZ', 'America/New_York') })
    afterEach(() => { vi.unstubAllEnvs() })

    it('keeps a date-only value on its own day', () => {
      expect(formatDate('2026-07-31', 'fr-FR', 'iso')).toBe('31-07-2026')
      expect(formatDate('2026-07-31', 'fr-FR', 'locale')).toBe('31/07/2026')
    })

    it('still honours the offset of a value that carries a time', () => {
      // 02:00 UTC is the previous day at 22:00 in New York, and that shift is real information
      // rather than a parsing artefact — the date-only rule must not swallow it.
      expect(formatDate('2026-07-31T02:00:00Z', 'fr-FR', 'iso')).toBe('30-07-2026')
    })
  })
})

describe('formatPercent', () => {
  it('formats percentage', () => {
    const result = formatPercent(0.5)
    expect(result).toContain('50')
  })
})

describe('localeFromLanguage', () => {
  it('maps supported app languages to browser locales', () => {
    expect(localeFromLanguage('fr')).toBe('fr-FR')
    expect(localeFromLanguage('fr-CA')).toBe('fr-FR')
    expect(localeFromLanguage('en')).toBe('en-US')
    expect(localeFromLanguage('de')).toBe('de-DE')
    expect(localeFromLanguage('de-AT')).toBe('de-DE')
    expect(localeFromLanguage('es')).toBe('es-ES')
  })

  it('falls back to the app default locale for unknown or missing tags', () => {
    // The app's fallback language is French (i18n fallbackLng: 'fr'),
    // so unresolvable tags map to fr-FR — not en-US.
    expect(localeFromLanguage(undefined)).toBe('fr-FR')
    expect(localeFromLanguage('it')).toBe('fr-FR')
  })
})

describe('todayLabel', () => {
  const monday = new Date('2026-07-06T12:00:00Z')

  it('capitalizes the weekday in French', () => {
    expect(todayLabel('fr-FR', monday)).toMatch(/^Lundi\b/)
  })

  it('keeps the weekday capitalized in English', () => {
    expect(todayLabel('en-US', monday)).toMatch(/^Monday\b/)
  })
})

describe('parseDate', () => {
  it('parses dd/mm/yyyy in fr-FR locale mode', () => {
    expect(parseDate('15/03/2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('parses mm/dd/yyyy in en-US locale mode (day/month swapped)', () => {
    // 03/15 must be read month-first → March 15th, not "day 15 month 3".
    expect(parseDate('03/15/2025', 'en-US', 'locale')).toBe('2025-03-15')
  })

  it('parses dd-mm-yyyy in iso mode regardless of locale', () => {
    expect(parseDate('15-03-2025', 'en-US', 'iso')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'iso')).toBe('2025-03-15')
  })

  it('accepts mixed separators (/, -, .)', () => {
    expect(parseDate('15.03.2025', 'fr-FR', 'locale')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('expands 2-digit years into the 2000s', () => {
    expect(parseDate('15/03/25', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('rejects impossible calendar dates', () => {
    expect(parseDate('31/02/2025', 'fr-FR', 'locale')).toBeNull() // no Feb 31
    expect(parseDate('00/01/2025', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/13/2025', 'fr-FR', 'locale')).toBeNull() // month 13
  })

  it('rejects malformed input', () => {
    expect(parseDate('', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('not a date', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/03', 'fr-FR', 'locale')).toBeNull() // too few parts
    expect(parseDate(null, 'fr-FR', 'locale')).toBeNull()
  })

  // The contract that matters: parseDate is the exact inverse of formatDate, so a
  // value rendered in the input can always be read back to the same ISO string.
  it('round-trips formatDate → parseDate across formats and locales', () => {
    const iso = '2025-03-15'
    const cases: Array<{ locale: string; format: 'iso' | 'locale' }> = [
      { locale: 'fr-FR', format: 'locale' },
      { locale: 'en-US', format: 'locale' },
      { locale: 'fr-FR', format: 'iso' },
      { locale: 'en-US', format: 'iso' },
    ]
    for (const { locale, format } of cases) {
      const displayed = formatDate(iso, locale, format)
      expect(parseDate(displayed, locale, format), `${locale}/${format} → ${displayed}`).toBe(iso)
    }
  })
})

describe('freshnessLevel', () => {
  const NOW = new Date('2026-08-11T12:00:00Z').getTime()
  const DAY = 24 * 60 * 60 * 1000
  const bounds = { fresh: DAY, recent: 2 * DAY, stale: 7 * DAY }
  const ago = (ms: number) => new Date(NOW - ms).toISOString()

  it('buckets an age against the bounds it is given', () => {
    expect(freshnessLevel(ago(60 * 60 * 1000), bounds, NOW)).toBe('fresh')
    expect(freshnessLevel(ago(1.5 * DAY), bounds, NOW)).toBe('recent')
    expect(freshnessLevel(ago(3 * DAY), bounds, NOW)).toBe('stale')
    expect(freshnessLevel(ago(30 * DAY), bounds, NOW)).toBe('old')
  })

  it('treats each bound as exclusive, so a level ends exactly where the next begins', () => {
    expect(freshnessLevel(ago(DAY - 1), bounds, NOW)).toBe('fresh')
    expect(freshnessLevel(ago(DAY), bounds, NOW)).toBe('recent')
  })

  /** Never synced is not "very old": it must not read as an alarm about ageing data. */
  it('reports a missing date as unknown rather than old', () => {
    expect(freshnessLevel(null, bounds, NOW)).toBe('unknown')
    expect(freshnessLevel(undefined, bounds, NOW)).toBe('unknown')
  })

  /** A date-only string is local midnight, not UTC -- the property valuation date's shape. */
  it('reads a date-only value without a timezone shift', () => {
    const today = new Date(NOW)
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
    expect(freshnessLevel(iso, bounds, NOW)).toBe('fresh')
  })

  /** Server clock ahead of the browser: an age below zero is as fresh as it gets, not old. */
  it('treats a future date as fresh', () => {
    expect(freshnessLevel(ago(-DAY), bounds, NOW)).toBe('fresh')
  })
})
