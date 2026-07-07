import { useEffect, useMemo } from 'react'
import { useForm, useWatch, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { Label } from '@/components/ui/label'
import { ColorPicker } from '@/components/shared/ColorPicker'
import { parseAmount, getLocale } from '@/lib/utils'
import { ACCOUNT_TYPES, SUPPORTED_CURRENCIES } from '@/lib/constants'

/** RHF setValueAs: empty → undefined (optional), else comma-tolerant number. */
const toOptionalNumber = (v: unknown): number | undefined =>
  v === '' || v == null ? undefined : parseAmount(String(v))
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const accountSchema = z.object({
  name: z.string().min(1).max(100),
  type: z.enum(['LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS', 'REAL_ESTATE', 'LOAN', 'OTHER']),
  provider: z.string().max(100).optional(),
  currency: z.string().min(1),
  currentBalance: z.number().min(0).optional(),
  isManual: z.boolean(),
  color: z.string(),
  ticker: z.string().max(20).optional(),
  // Loan-only fields (validated as numbers but optional at the form level — required-ness is enforced at submit when type=LOAN)
  borrowedAmount: z.number().min(0).optional(),
  interestRatePct: z.number().min(0).max(100).optional(),
  monthlyPayment: z.number().min(0).optional(),
  insuranceMonthly: z.number().min(0).optional(),
  fileFees: z.number().min(0).optional(),
  startDate: z.string().optional(),
  endDate: z.string().optional(),
})

type AccountFormData = z.infer<typeof accountSchema>

interface AccountFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (data: AccountFormData) => void
  defaultValues?: Partial<AccountFormData>
  title?: string
  loading?: boolean
}

const EMPTY_DEFAULTS: AccountFormData = {
  name: '',
  type: 'CHECKING',
  provider: '',
  currency: 'EUR',
  currentBalance: undefined,
  isManual: false,
  color: '#6366f1',
  ticker: '',
  borrowedAmount: undefined,
  interestRatePct: undefined,
  monthlyPayment: undefined,
  insuranceMonthly: undefined,
  fileFees: undefined,
  startDate: '',
  endDate: '',
}

const selectControlClassName = "flex h-10 w-full rounded-xl border border-input bg-input/20 px-4 text-sm outline-none dark:bg-input/30"

export function AccountForm({ open, onOpenChange, onSubmit, defaultValues, title, loading }: AccountFormProps) {
  const { t } = useTranslation()
  const { register, handleSubmit, setValue, reset, control } = useForm<AccountFormData>({
    resolver: zodResolver(accountSchema),
    defaultValues: { ...EMPTY_DEFAULTS, ...defaultValues },
  })

  const selectedColor = useWatch({ control, name: 'color' })
  const selectedType = useWatch({ control, name: 'type' })
  const selectedCurrency = useWatch({ control, name: 'currency' })

  // Build the currency dropdown options. Labels are resolved live via Intl.DisplayNames
  // (locale-aware, e.g. "EUR — Euro"). If the account being edited carries a code not in
  // the curated list (a legacy or previously-invalid value), prepend it so opening the
  // form for edit never silently rewrites the account's currency — issue #9.
  const currencyOptions = useMemo(() => {
    const codes =
      selectedCurrency && !(SUPPORTED_CURRENCIES as readonly string[]).includes(selectedCurrency)
        ? [selectedCurrency, ...SUPPORTED_CURRENCIES]
        : [...SUPPORTED_CURRENCIES]
    const display = new Intl.DisplayNames([getLocale()], { type: 'currency' })
    return codes.map((code) => {
      let name: string | undefined
      try {
        name = display.of(code)
      } catch {
        name = undefined
      }
      return { code, label: name && name !== code ? `${code} — ${name}` : code }
    })
  }, [selectedCurrency])

  // The dialog can be opened directly by the parent (open prop flips to true) — Radix's
  // onOpenChange does NOT fire in that case, so a one-shot reset on open inside the handler
  // is unreliable. Instead, sync the form via effect every time the dialog opens or the
  // editing target changes.
  useEffect(() => {
    if (open) {
      reset({ ...EMPTY_DEFAULTS, ...defaultValues })
    }
  }, [open, defaultValues, reset])

  function handleFormSubmit(data: AccountFormData) {
    onSubmit(data)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{title ?? t('accounts.addAccount')}</DialogTitle>
          <DialogDescription />
        </DialogHeader>
        <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">{t('accounts.accountName')}</Label>
            <Input id="name" {...register('name')} placeholder="PEA Boursorama" />
          </div>

          <div className="space-y-2">
            <Label htmlFor="type">{t('accounts.accountType')}</Label>
            <select
              id="type"
              {...register('type')}
              className={selectControlClassName}
            >
              {ACCOUNT_TYPES.map((at) => (
                <option key={at.value} value={at.value}>
                  {t(at.labelKey)}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="currency">{t('common.currency')}</Label>
              <select
                id="currency"
                {...register('currency')}
                className={selectControlClassName}
              >
                {currencyOptions.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="balance">
                {selectedType === 'LOAN' ? t('debt.remaining') : t('accounts.balance')}
              </Label>
              <NumericInput id="balance" {...register('currentBalance', { setValueAs: toOptionalNumber })} />
            </div>
          </div>

          {selectedType !== 'REAL_ESTATE' && selectedType !== 'LOAN' && (
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="provider">{t('accounts.provider')}</Label>
                <Input id="provider" {...register('provider')} placeholder="Boursorama" />
              </div>
              <div className="space-y-2">
                <Label htmlFor="ticker">{t('accounts.ticker')}</Label>
                <Input id="ticker" {...register('ticker')} placeholder="BTC" />
              </div>
            </div>
          )}

          {selectedType === 'LOAN' && (
            <>
              <div className="space-y-2">
                <Label htmlFor="provider">{t('debt.lenderName')}</Label>
                <Input id="provider" {...register('provider')} placeholder={t('debt.lenderName')} />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="borrowedAmount">{t('debt.borrowedAmount')}</Label>
                  <NumericInput
                    id="borrowedAmount"
                    {...register('borrowedAmount', { setValueAs: toOptionalNumber })}
                    placeholder="100000"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="interestRatePct">{t('debt.interestRate')} (%)</Label>
                  <NumericInput
                    id="interestRatePct"
                    {...register('interestRatePct', { setValueAs: toOptionalNumber })}
                    placeholder="1.5"
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="monthlyPayment">{t('debt.monthlyPayment')}</Label>
                  <NumericInput
                    id="monthlyPayment"
                    {...register('monthlyPayment', { setValueAs: toOptionalNumber })}
                    placeholder="394.40"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="insuranceMonthly">{t('debt.insuranceMonthly')}</Label>
                  <NumericInput
                    id="insuranceMonthly"
                    {...register('insuranceMonthly', { setValueAs: toOptionalNumber })}
                    placeholder="0"
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="fileFees">{t('debt.fileFees')}</Label>
                  <NumericInput
                    id="fileFees"
                    {...register('fileFees', { setValueAs: toOptionalNumber })}
                    placeholder="0"
                  />
                </div>
                <div className="space-y-2">
                  {/* spacer to keep grid aligned */}
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="startDate">{t('debt.startDate')}</Label>
                  <Controller
                    control={control}
                    name="startDate"
                    render={({ field }) => (
                      <DateInput id="startDate" value={field.value ?? ''} onChange={field.onChange} />
                    )}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="endDate">{t('debt.endDate')}</Label>
                  <Controller
                    control={control}
                    name="endDate"
                    render={({ field }) => (
                      <DateInput id="endDate" value={field.value ?? ''} onChange={field.onChange} />
                    )}
                  />
                </div>
              </div>
            </>
          )}

          <div className="space-y-2">
            <Label>{t('accounts.color')}</Label>
            <ColorPicker value={selectedColor} onChange={(c) => setValue('color', c)} />
          </div>

          {selectedType !== 'REAL_ESTATE' && selectedType !== 'LOAN' && (
            <div className="flex min-h-10 items-center gap-2">
              <input id="isManual" type="checkbox" {...register('isManual')} className="h-5 w-5 rounded accent-primary" />
              <Label htmlFor="isManual">{t('accounts.manual')}</Label>
            </div>
          )}

          {(selectedType === 'REAL_ESTATE' || selectedType === 'LOAN') && (
            <input type="hidden" {...register('isManual')} value="true" />
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={loading}>
              {loading ? t('common.loading') : t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
