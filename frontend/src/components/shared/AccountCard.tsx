import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import type { Account } from '@/types/api'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCurrency, formatDate, formatTimeAgo, localeFromLanguage } from '@/lib/utils'
import { logoKeyUrl, providerLogoUrl } from '@/lib/provider-logos'

interface AccountCardProps {
  account: Account
  onClick?: () => void
}

/**
 * Synced accounts whose data is older than this are flagged: live prices keep
 * the numbers moving, so without an explicit signal a dead provider session
 * (e.g. Trade Republic) looks perfectly healthy.
 */
const SYNC_STALE_THRESHOLD_MS = 48 * 60 * 60 * 1000

/**
 * The bundled asset the account itself points at (a wallet's `logoKey`) when it has one, else
 * the provider's own logo when the connector supplied one (Enable Banking), else the brand
 * asset bundled for that provider, else the account's color.
 */
function AccountAvatar(
  { logoKey, logoUrl, provider, color }:
    { logoKey: string | null; logoUrl: string | null; provider: string | null; color: string },
) {
  // The account's own key first: it is the only one a user picked by hand, so it outranks
  // both the connector's logo and the provider map.
  const src = logoKeyUrl(logoKey) ?? logoUrl ?? providerLogoUrl(provider)
  return (
    <Avatar className="mt-1 size-10 shrink-0 bg-white">
      {src && <AvatarImage src={src} alt="" className="object-contain p-1" />}
      <AvatarFallback style={{ backgroundColor: color }} />
    </Avatar>
  )
}

export function AccountCard({ account, onClick }: AccountCardProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const isLoan = account.type === 'LOAN'
  const isRealEstate = account.type === 'REAL_ESTATE'

  // Lazy initializer keeps the impure Date.now() out of render; the slow tick
  // lets a long-lived tab cross the 48h threshold without a remount (the whole
  // point of the badge is catching sessions that die while the app sits open).
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5 * 60 * 1000)
    return () => clearInterval(id)
  }, [])
  const isSyncStale =
    !account.isManual &&
    account.lastSyncedAt != null &&
    now - new Date(account.lastSyncedAt).getTime() > SYNC_STALE_THRESHOLD_MS

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
        <AccountAvatar
          logoKey={account.logoKey}
          logoUrl={account.logoUrl}
          provider={account.provider}
          color={account.color}
        />
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
            isSyncStale ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <p className="mt-1 flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
                      <TriangleAlert className="size-3 shrink-0" />
                      {t('accounts.syncStale', { time: formatTimeAgo(account.lastSyncedAt) })}
                    </p>
                  </TooltipTrigger>
                  <TooltipContent className="max-w-xs">{t('accounts.syncStaleTooltip')}</TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : (
              <p className="mt-1 text-xs text-muted-foreground">
                {t('accounts.lastSync')}: {formatDate(account.lastSyncedAt)}
              </p>
            )
          )}
        </div>
      </CardContent>
    </Card>
  )
}
