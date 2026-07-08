import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { Label } from '@/components/ui/label'
import { formatCurrency, localeFromLanguage, parseAmount } from '@/lib/utils'
import { extractErrorMessage } from '@/lib/errors'
import { Loader2 } from 'lucide-react'
import type { AccountType, TransactionRequest } from '@/types/api'
import { accountsApi } from '@/features/accounts/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

const INVESTMENT_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO']

type InitialValues = TransactionRequest & { id?: number }

const today = () => new Date().toISOString().split('T')[0]

interface AddTransactionModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
  initialValues?: InitialValues
}

export function AddTransactionModal({ open, onOpenChange, initialValues, ...rest }: AddTransactionModalProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{initialValues ? t('accounts.editTransaction') : t('accounts.addTransaction')}</DialogTitle>
        </DialogHeader>
        {/* Remount a fresh form each time the dialog opens (key changes per
            edited transaction) so initial state derives straight from
            `initialValues` — no populate/reset effect needed. */}
        {open && (
          <TransactionForm
            key={initialValues?.id ?? 'new'}
            onOpenChange={onOpenChange}
            initialValues={initialValues}
            {...rest}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

interface TransactionFormProps {
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
  initialValues?: InitialValues
}

function TransactionForm({ onOpenChange, accountId, accountType, onSubmit, isLoading, initialValues }: TransactionFormProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const isInvestment = INVESTMENT_TYPES.includes(accountType)
  const isInvestmentTx = initialValues?.txType === 'BUY' || initialValues?.txType === 'SELL'

  const { data: holdings } = useQuery({
    queryKey: ['accounts', accountId, 'holdings'],
    queryFn: () => accountsApi.holdings(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: isInvestment && !!accountId,
  })

  // Shared state — initialized from initialValues (edit) or sensible defaults (add)
  const [date, setDate] = useState(() => (initialValues?.date ? String(initialValues.date) : today()))
  const [description, setDescription] = useState(() => (!isInvestmentTx ? (initialValues?.description ?? '') : ''))
  const [error, setError] = useState<string | null>(null)

  // Cash fields
  const [txDirection, setTxDirection] = useState<'deposit' | 'withdrawal'>(() =>
    !isInvestmentTx && initialValues?.amount != null && Number(initialValues.amount) < 0 ? 'withdrawal' : 'deposit'
  )
  const [cashAmount, setCashAmount] = useState(() =>
    !isInvestmentTx && initialValues?.amount != null ? String(Math.abs(Number(initialValues.amount))) : ''
  )

  // Investment fields
  const [investType, setInvestType] = useState<'BUY' | 'SELL'>(() => (isInvestmentTx ? (initialValues!.txType as 'BUY' | 'SELL') : 'BUY'))
  const [ticker, setTicker] = useState(() => (isInvestmentTx ? (initialValues?.ticker ?? '') : ''))
  const [name, setName] = useState(() => (isInvestmentTx ? (initialValues?.name ?? '') : ''))
  const [quantity, setQuantity] = useState(() => (isInvestmentTx && initialValues?.quantity != null ? String(initialValues.quantity) : ''))
  const [pricePerUnit, setPricePerUnit] = useState(() => (isInvestmentTx && initialValues?.pricePerUnit != null ? String(initialValues.pricePerUnit) : ''))

  // Auto-fill the name from an existing holding when the ticker matches —
  // done in the change handler (not an effect) so it stays out of render.
  function handleTickerChange(value: string) {
    setTicker(value)
    if (holdings && value) {
      const match = holdings.find(h => h.ticker.toUpperCase() === value.toUpperCase())
      if (match?.name) setName(match.name)
    }
  }

  const total = quantity && pricePerUnit
    ? parseAmount(quantity) * parseAmount(pricePerUnit)
    : null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    let data: TransactionRequest

    if (isInvestment) {
      const qty = parseAmount(quantity)
      const price = parseAmount(pricePerUnit)
      const amount = investType === 'BUY' ? -(qty * price) : (qty * price)
      data = {
        date,
        description: name || (investType === 'BUY' ? `Achat ${ticker}` : `Vente ${ticker}`),
        amount,
        txType: investType,
        ticker: ticker.toUpperCase(),
        name: name.trim() || undefined,
        quantity: qty,
        pricePerUnit: price,
      }
    } else {
      const raw = parseAmount(cashAmount)
      const amount = txDirection === 'deposit' ? Math.abs(raw) : -Math.abs(raw)
      data = {
        date,
        description,
        amount,
        txType: txDirection === 'deposit' ? 'DEPOSIT' : 'WITHDRAWAL',
      }
    }

    try {
      await onSubmit(data)
      onOpenChange(false)
    } catch (err) {
      setError(extractErrorMessage(err, t('common.error')))
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {/* Date */}
      <div className="space-y-1">
        <Label>{t('accounts.transactionDate')}</Label>
        <DateInput value={date} onChange={setDate} required />
      </div>

      {isInvestment ? (
        <>
          {/* BUY / SELL toggle */}
          <div className="flex gap-2">
            {(['BUY', 'SELL'] as const).map(type => (
              <Button
                key={type}
                type="button"
                variant={investType === type ? 'default' : 'outline'}
                size="sm"
                onClick={() => setInvestType(type)}
              >
                {type === 'BUY' ? t('accounts.buy') : t('accounts.sell')}
              </Button>
            ))}
          </div>
          <div className="space-y-1">
            <Label>{t('accounts.tickerOrIsin')}</Label>
            <Input placeholder={t('accounts.tickerOrIsinPlaceholder')} value={ticker} onChange={e => handleTickerChange(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>{t('holdings.name')} <span className="text-muted-foreground text-xs">({t('common.optional')})</span></Label>
            <Input placeholder={t('accounts.assetNamePlaceholder')} value={name} onChange={e => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{t('holdings.quantity')}</Label>
            <NumericInput value={quantity} onChange={e => setQuantity(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>{t('holdings.unitPrice')}</Label>
            <NumericInput value={pricePerUnit} onChange={e => setPricePerUnit(e.target.value)} required />
          </div>
          <p className="text-sm text-muted-foreground">
            {t('accounts.total')}: {total != null && Number.isFinite(total) ? formatCurrency(total, 'EUR', locale) : '—'}
          </p>
        </>
      ) : (
        <>
          {/* DEPOSIT / WITHDRAWAL toggle */}
          <div className="flex gap-2">
            <Button
              type="button"
              variant={txDirection === 'deposit' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setTxDirection('deposit')}
            >
              + {t('accounts.deposit')}
            </Button>
            <Button
              type="button"
              variant={txDirection === 'withdrawal' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setTxDirection('withdrawal')}
            >
              − {t('accounts.withdrawal')}
            </Button>
          </div>
          <div className="space-y-1">
            <Label>{t('accounts.description')}</Label>
            <Input value={description} onChange={e => setDescription(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <Label>{t('accounts.amount')}</Label>
            <NumericInput value={cashAmount} onChange={e => setCashAmount(e.target.value)} required />
          </div>
        </>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t('common.cancel')}</Button>
        <Button type="submit" disabled={isLoading}>
          {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          {initialValues ? t('common.save') : t('common.create')}
        </Button>
      </DialogFooter>
    </form>
  )
}
