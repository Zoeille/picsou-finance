import { useTranslation } from 'react-i18next'
import { formatCurrency, localeFromLanguage } from '@/lib/utils'

interface CurrencyDisplayProps {
  value: number
  currency?: string
  className?: string
  showSign?: boolean
}

export function CurrencyDisplay({ value, currency, className, showSign = false }: CurrencyDisplayProps) {
  const { i18n } = useTranslation()
  const cur = currency || 'EUR'
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const formatted = formatCurrency(Math.abs(value), cur, locale)
  const sign = showSign && value >= 0 ? '+' : value < 0 ? '-' : ''

  return (
    <span className={className}>
      {sign}{formatted}
    </span>
  )
}
