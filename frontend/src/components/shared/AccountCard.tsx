import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import type { CSSProperties } from 'react'
import type { Account, PropertyKind } from '@/types/api'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCurrency, formatDate, formatTimeAgo, localeFromLanguage } from '@/lib/utils'
import { logoKeyUrl, providerLogoUrl } from '@/lib/provider-logos'
import { PROPERTY_KIND_ICONS } from '@/lib/property-icons'

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

/**
 * A property has no provider to borrow a logo from, so its kind is its mark: the account
 * color tints the disc and the glyph says what the asset is, which is what makes a house
 * tellable from a parking space at a glance in the grid.
 *
 * The glyph is the same hue as the disc, pushed to a readable lightness per theme with the
 * relative color syntax the theme ADR prescribes — the account palette runs from indigo to
 * yellow, and a raw yellow-500 mark on its own pale tint would be barely visible in light
 * mode.
 */
function PropertyAvatar({ kind, color }: { kind: PropertyKind; color: string }) {
  const Icon = PROPERTY_KIND_ICONS[kind]
  return (
    <Avatar className="mt-1 size-10 shrink-0">
      <AvatarFallback
        style={{ '--account-color': color } as CSSProperties}
        className="bg-[color-mix(in_oklch,var(--account-color)_16%,var(--card))] text-[color:oklch(from_var(--account-color)_calc(l_-_0.18)_c_h)] dark:text-[color:oklch(from_var(--account-color)_calc(l_+_0.12)_c_h)]"
      >
        <Icon className="size-5" aria-hidden />
      </AvatarFallback>
    </Avatar>
  )
}

export function AccountCard({ account, onClick }: AccountCardProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const isLoan = account.type === 'LOAN'
  const property = account.type === 'REAL_ESTATE' ? account.realEstate : undefined
  const propertyKind = property?.propertyKind ?? null

  // Every card reads the same way top to bottom: mark, name, who or what it is, balance,
  // when the figure was last refreshed. A property is manual, so it has no provider and no
  // lastSyncedAt to fill the middle and bottom lines -- its kind and city stand in for the
  // provider, and the valuation date for the sync date. Unrealised gain used to sit between
  // them, which no other account type shows; it lives on the detail page and the real-estate
  // summary card instead.
  const subtitle = property
    ? [propertyKind && t(`property.kind.${propertyKind}`), property.city]
        .filter(Boolean)
        .join(' · ')
    : account.provider

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

  // Only a sync date can go stale: a valuation date comes from a manual account the
  // staleness badge deliberately ignores, and an old estimate is not a dead session.
  const freshness = property?.lastValuedAt
    ? { label: t('accounts.lastValuation'), date: property.lastValuedAt, stale: false }
    : account.lastSyncedAt
      ? { label: t('accounts.lastSync'), date: account.lastSyncedAt, stale: isSyncStale }
      : null

  return (
    <Card
      className="cursor-pointer transition-colors hover:bg-muted/20"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-4">
        {propertyKind ? (
          <PropertyAvatar kind={propertyKind} color={account.color} />
        ) : (
          <AccountAvatar
            logoKey={account.logoKey}
            logoUrl={account.logoUrl}
            provider={account.provider}
            color={account.color}
          />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium">{account.name}</span>
            <AccountTypeBadge type={account.type} />
            {/* Present only below 100%, so the balance above is knowingly a shared figure. */}
            {account.sharePercent != null && (
              <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                {Number(account.sharePercent).toFixed(0)} %
              </span>
            )}
          </div>
          {subtitle && (
            <p className="truncate text-xs text-muted-foreground">{subtitle}</p>
          )}
          <div className="mt-2">
            <CurrencyDisplay
              value={isLoan ? -account.currentBalanceEur : account.currentBalanceEur}
              currency={account.currency}
              className={`text-lg font-semibold ${isLoan ? 'text-red-500' : ''}`}
            />
          </div>
          {isLoan && account.debt && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('debt.borrowedAmount')}: {formatCurrency(account.debt.borrowedAmount, 'EUR', locale)}
            </p>
          )}
          {freshness && (
            freshness.stale ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <p className="mt-1 flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
                      <TriangleAlert className="size-3 shrink-0" />
                      {t('accounts.syncStale', { time: formatTimeAgo(freshness.date) })}
                    </p>
                  </TooltipTrigger>
                  <TooltipContent className="max-w-xs">{t('accounts.syncStaleTooltip')}</TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : (
              <p className="mt-1 text-xs text-muted-foreground">
                {freshness.label}: {formatDate(freshness.date)}
              </p>
            )
          )}
        </div>
      </CardContent>
    </Card>
  )
}
