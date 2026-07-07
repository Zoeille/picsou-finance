import { useTranslation } from 'react-i18next'
import { useIntlLocale } from '@/hooks/use-intl-locale'
import { formatCurrency } from '@/lib/utils'

interface CurrencyDisplayProps {
  value: number
  currency?: string
  className?: string
  showSign?: boolean
}

export function CurrencyDisplay({ value, currency, className, showSign = false }: CurrencyDisplayProps) {
  const { t } = useTranslation()
  const cur = currency || t('common.currency')
  const locale = useIntlLocale()

  const formatted = formatCurrency(Math.abs(value), cur, locale)
  const sign = showSign && value >= 0 ? '+' : value < 0 ? '-' : ''

  return (
    <span className={className}>
      {sign}{formatted}
    </span>
  )
}
