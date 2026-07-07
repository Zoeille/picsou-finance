import { useTranslation } from 'react-i18next'
import type { HoldingResponse } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PriceFreshnessDot } from '@/components/shared/PriceFreshnessDot'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { Pencil, Trash2 } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

interface HoldingsTableProps {
  holdings: HoldingResponse[]
  onEdit?: (holding: HoldingResponse) => void
  onDelete?: (holding: HoldingResponse) => void
}

export function HoldingsTable({ holdings, onEdit, onDelete }: HoldingsTableProps) {
  const { t } = useTranslation()

  if (holdings.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('accounts.holdings')}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t('holdings.ticker')}</TableHead>
              <TableHead>{t('holdings.name')}</TableHead>
              <TableHead className="text-right">{t('holdings.quantity')}</TableHead>
              <TableHead className="text-right">{t('holdings.avgBuyIn')}</TableHead>
              <TableHead className="text-right">{t('holdings.assetPrice')}</TableHead>
              <TableHead className="text-right">{t('portfolio.value')}</TableHead>
              <TableHead className="text-right">{t('holdings.pnl')}</TableHead>
              <TableHead className="text-right">{t('holdings.pnlPercent')}</TableHead>
              {(onEdit || onDelete) && <TableHead />}
            </TableRow>
          </TableHeader>
          <TableBody>
            {holdings.map((h) => (
              <TableRow key={h.ticker}>
                <TableCell className="font-mono font-medium">{h.ticker}</TableCell>
                <TableCell>{h.name ?? h.ticker}</TableCell>
                <TableCell className="text-right">{h.quantity}</TableCell>
                <TableCell className="text-right">
                  {h.averageBuyIn ? <CurrencyDisplay value={h.averageBuyIn} className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className="text-right">
                  <div className="inline-flex items-center gap-1.5">
                    <PriceFreshnessDot priceUpdatedAt={h.priceUpdatedAt} />
                    {h.currentPrice ? <CurrencyDisplay value={h.currentPrice} className="text-sm" /> : '\u2014'}
                  </div>
                </TableCell>
                <TableCell className="text-right font-medium">
                  {h.currentValueEur ? <CurrencyDisplay value={h.currentValueEur} className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className={cn('text-right', h.pnlEur != null && h.pnlEur >= 0 ? 'text-emerald-500' : h.pnlEur != null && h.pnlEur < 0 ? 'text-red-500' : '')}>
                  {h.pnlEur != null ? <CurrencyDisplay value={h.pnlEur} showSign className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className={cn('text-right', h.pnlPercent != null && h.pnlPercent >= 0 ? 'text-emerald-500' : h.pnlPercent != null && h.pnlPercent < 0 ? 'text-red-500' : '')}>
                  {h.pnlPercent != null ? `${h.pnlPercent >= 0 ? '+' : ''}${h.pnlPercent.toFixed(1)}%` : '\u2014'}
                </TableCell>
                {(onEdit || onDelete) && (
                  <TableCell className="text-right">
                    <div className="inline-flex items-center gap-1">
                      {onEdit && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-foreground"
                          onClick={() => onEdit(h)}
                        >
                          <Pencil className="size-4" />
                        </Button>
                      )}
                      {onDelete && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-destructive"
                          onClick={() => onDelete(h)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
