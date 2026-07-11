import { useTranslation } from 'react-i18next'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn, localeFromLanguage } from '@/lib/utils'
import { TrendingDown, TrendingUp } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useRealizedPnl } from '@/features/accounts/hooks'

interface RealizedPnlSectionProps {
  accountId: number
  /** Only investment accounts have realized P&L; skip the query otherwise. */
  enabled?: boolean
}

/**
 * Realized gains/losses on closed (fully or partially sold) positions. Surfaces the P&L that
 * vanishes from the holdings view once a position is sold. Renders nothing until there is at
 * least one sell.
 */
export function RealizedPnlSection({ accountId, enabled = true }: RealizedPnlSectionProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const { data } = useRealizedPnl(accountId, enabled)

  // Defensive: demo mode returns {} for unhandled endpoints, so guard `lots` too.
  if (!data || !data.lots || data.lots.length === 0) return null

  const { currency, realizedTotal, lots } = data
  const positive = realizedTotal >= 0

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(locale)
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{t('realized.title')}</CardTitle>
        <div className={cn('flex items-center gap-1.5 font-medium', positive ? 'text-emerald-500' : 'text-red-500')}>
          {positive ? <TrendingUp className="size-4" /> : <TrendingDown className="size-4" />}
          <CurrencyDisplay value={realizedTotal} currency={currency} showSign />
        </div>
      </CardHeader>
      <CardContent>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('holdings.ticker')}</TableHead>
                <TableHead>{t('holdings.name')}</TableHead>
                <TableHead>{t('accounts.transactionDate')}</TableHead>
                <TableHead className="text-right">{t('realized.qtySold')}</TableHead>
                <TableHead className="text-right">{t('realized.avgCost')}</TableHead>
                <TableHead className="text-right">{t('realized.proceeds')}</TableHead>
                <TableHead className="text-right">{t('realized.realizedGains')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {lots.map((lot, i) => (
                <TableRow key={`${lot.ticker}-${lot.date}-${i}`}>
                  <TableCell className="font-mono font-medium">{lot.ticker}</TableCell>
                  <TableCell>{lot.name ?? lot.ticker}</TableCell>
                  <TableCell>{formatDate(lot.date)}</TableCell>
                  <TableCell className="text-right">{lot.quantity}</TableCell>
                  <TableCell className="text-right">
                    <CurrencyDisplay value={lot.avgCost} currency={currency} className="text-sm" />
                  </TableCell>
                  <TableCell className="text-right">
                    <CurrencyDisplay value={lot.proceeds} currency={currency} className="text-sm" />
                  </TableCell>
                  <TableCell className={cn('text-right', lot.realized >= 0 ? 'text-emerald-500' : 'text-red-500')}>
                    <CurrencyDisplay value={lot.realized} currency={currency} showSign className="text-sm" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}
