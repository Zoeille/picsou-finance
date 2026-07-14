import { useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useBankCountries } from '@/features/sync/hooks'
import { formatCountryName } from '@/lib/utils'

interface BankCountrySelectProps {
  value: string
  onChange: (country: string) => void
}

/** Picsou's primary market — the initial/fallback selection everywhere a country picker appears. */
export const DEFAULT_BANK_COUNTRY = 'FR'

const FALLBACK_COUNTRIES = [DEFAULT_BANK_COUNTRY]

/**
 * Country picker for the bank search step (AddAccountModal's BankWizard, BankSyncTab).
 * Options come live from `GET /api/sync/countries` (whatever the active provider actually
 * covers) rather than a hardcoded list, so coverage never drifts from reality. The backend
 * already returns codes sorted; this just pins the default market first for convenience.
 */
export function BankCountrySelect({ value, onChange }: BankCountrySelectProps) {
  const { t } = useTranslation()
  const { data: countries } = useBankCountries()

  const options = useMemo(() => {
    const codes = countries && countries.length > 0 ? countries : FALLBACK_COUNTRIES
    const ordered = codes.includes(DEFAULT_BANK_COUNTRY)
      ? [DEFAULT_BANK_COUNTRY, ...codes.filter((c) => c !== DEFAULT_BANK_COUNTRY)]
      : codes
    return ordered.map((code) => ({ code, label: formatCountryName(code) }))
  }, [countries])

  // If the current value isn't in the loaded list (e.g. the active provider has no
  // DEFAULT_BANK_COUNTRY coverage), the native <select> would render blank while searches
  // silently kept filtering by the invisible stale value — snap to the first real option.
  useEffect(() => {
    if (options.length > 0 && !options.some((o) => o.code === value)) {
      onChange(options[0].code)
    }
  }, [options, value, onChange])

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      aria-label={t('sync.banks.country')}
      className="h-10 shrink-0 rounded-md border border-input bg-input/20 px-4 text-sm outline-none dark:bg-input/30"
    >
      {options.map((o) => (
        <option key={o.code} value={o.code}>{o.label}</option>
      ))}
    </select>
  )
}
