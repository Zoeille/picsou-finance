import { useTranslation } from 'react-i18next'
import type { Account } from '@/types/api'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { formatCurrency, formatDate, localeFromLanguage } from '@/lib/utils'

interface AccountCardProps {
  account: Account
  onClick?: () => void
}

function AccountAvatar({ logoUrl, color }: { logoUrl: string | null; color: string }) {
  return (
    <Avatar className="mt-1 size-10 shrink-0 bg-white">
      {logoUrl && <AvatarImage src={logoUrl} alt="" className="object-contain p-1" />}
      <AvatarFallback style={{ backgroundColor: color }} />
    </Avatar>
  )
}

export function AccountCard({ account, onClick }: AccountCardProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const isLoan = account.type === 'LOAN'
  const isRealEstate = account.type === 'REAL_ESTATE'

  const pnl = isRealEstate && account.realEstate
    ? account.currentBalanceEur - account.realEstate.purchasePrice
    : null
  const pnlPct = isRealEstate && account.realEstate && account.realEstate.purchasePrice > 0
    ? ((pnl! / account.realEstate.purchasePrice) * 100).toFixed(1)
    : null

  return (
    <Card
      className="cursor-pointer transition-colors hover:bg-muted/20"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-4">
        <AccountAvatar logoUrl={account.logoUrl} color={account.color} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium">{account.name}</span>
            <AccountTypeBadge type={account.type} />
          </div>
          {account.provider && (
            <p className="text-xs text-muted-foreground">{account.provider}</p>
          )}
          <div className="mt-2">
            <CurrencyDisplay
              value={isLoan ? -account.currentBalanceEur : account.currentBalanceEur}
              currency={account.currency}
              className={`text-lg font-semibold ${isLoan ? 'text-red-500' : ''}`}
            />
          </div>
          {isRealEstate && pnl !== null && (
            <p className={`mt-1 text-xs ${pnl >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
              {pnl >= 0 ? '+' : ''}{formatCurrency(pnl, 'EUR', locale)}
              {pnlPct !== null && ` (${pnl >= 0 ? '+' : ''}${pnlPct}%)`}
            </p>
          )}
          {isLoan && account.debt && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('debt.borrowedAmount')}: {formatCurrency(account.debt.borrowedAmount, 'EUR', locale)}
            </p>
          )}
          {account.lastSyncedAt && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('accounts.lastSync')}: {formatDate(account.lastSyncedAt)}
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
