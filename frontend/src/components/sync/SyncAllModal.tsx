import { useState, useCallback, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState } from '@/components/shared/EmptyState'
import { Skeleton } from '@/components/ui/skeleton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import {
  Loader2,
  RefreshCw,
  ExternalLink,
  Landmark,
  Coins,
  Wallet,
  Building2,
  LineChart,
  Info,
  Smartphone,
  Lock,
  ShieldCheck,
  User,
} from 'lucide-react'
import {
  useBankSyncStatus,
  useCryptoExchangeStatuses,
  useCryptoWallets,
  useTrSessionStatus,
  useBoursoSessionStatus,
  useFinaryConnectionStatus,
  useRetryBankSync,
  useSyncCryptoExchange,
  useSyncCryptoWallet,
  useSyncTradeRepublic,
  useInitiateTrAuth,
  useCompleteTrAuth,
  useSyncBourso,
  useInitiateBoursoAuth,
  useCompleteBoursoAuth,
} from '@/features/sync/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { formatTimeAgo } from '@/lib/utils'
import { TR_VERIFICATION_CODE_LENGTH } from '@/lib/constants'

type SyncConnection = {
  id: string
  providerType: 'bank' | 'exchange' | 'wallet' | 'tr' | 'finary' | 'bourso'
  name: string
  status: string
  lastSyncedAt: string | null
  syncId?: number
}


const ProviderIcon: Record<SyncConnection['providerType'], React.ComponentType<{ className?: string }>> = {
  bank: Landmark,
  exchange: Coins,
  wallet: Wallet,
  tr: Building2,
  finary: LineChart,
  bourso: Building2,
}

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'LINKED':
    case 'CONNECTED':
    case 'active':
      return 'default'
    case 'CREATED':
      return 'secondary'
    case 'SESSION_EXPIRED':
    case 'EXPIRED':
      return 'outline'
    case 'FAILED':
    case 'ERROR':
      return 'destructive'
    default:
      return 'outline'
  }
}

interface SyncAllModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function SyncAllModal({ open, onOpenChange }: SyncAllModalProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // Queries
  const { data: banks, isLoading: banksLoading } = useBankSyncStatus()
  const { data: exchanges, isLoading: exchangesLoading } = useCryptoExchangeStatuses()
  const { data: wallets, isLoading: walletsLoading } = useCryptoWallets()
  const { data: trStatus } = useTrSessionStatus()
  const { data: boursoStatus } = useBoursoSessionStatus()
  const { data: finaryStatus } = useFinaryConnectionStatus()
  const { data: accounts } = useAccounts()

  // Detect if user has a TR / BoursoBank account
  const hasTrAccount     = accounts?.some(a => a.provider === 'Trade Republic') ?? false
  // BoursoBank disabled for 1.0.0 — sidecar integration not finished.
  const hasBoursoAccount = false

  // Mutations
  const retryBankMutation    = useRetryBankSync()
  const syncExchangeMutation = useSyncCryptoExchange()
  const syncWalletMutation   = useSyncCryptoWallet()
  const syncTrMutation       = useSyncTradeRepublic()
  const initiateTrMutation   = useInitiateTrAuth()
  const completeTrMutation   = useCompleteTrAuth()
  const syncBoursoMutation   = useSyncBourso()
  const initiateBoursoMutation = useInitiateBoursoAuth()
  const completeBoursoMutation = useCompleteBoursoAuth()

  // Track syncing state per connection
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set())

  // TR inline auth state
  const [trAuthStep, setTrAuthStep] = useState<'idle' | 'phone' | 'tan'>('idle')
  const [trPhone, setTrPhone] = useState('')
  const [trPin, setTrPin] = useState('')
  const [trTan, setTrTan] = useState('')
  const [trProcessId, setTrProcessId] = useState<string | null>(null)

  // BoursoBank inline auth state
  const [boursoAuthStep, setBoursoAuthStep] = useState<'idle' | 'credentials' | 'mfa'>('idle')
  const [boursoCustomerId, setBoursoCustomerId] = useState('')
  const [boursoPassword, setBoursoPassword] = useState('')
  const [boursoMfaCode, setBoursoMfaCode] = useState('')
  const [boursoProcessId, setBoursoProcessId] = useState<string | null>(null)
  const [boursoMfaInfo, setBoursoMfaInfo] = useState<{ type: string; contact: string } | null>(null)

  const isLoading = banksLoading || exchangesLoading || walletsLoading

  // Build unified connections list. Memoized so its identity is stable
  // across renders — otherwise it would invalidate the useCallback hooks
  // below (and the compiler's exhaustive-deps check) on every render.
  const connections = useMemo<SyncConnection[]>(() => {
    const list: SyncConnection[] = []
    if (banks) {
      banks
        .filter(b => b.status !== 'CREATED')
        .forEach(b => {
          list.push({
            id: `bank-${b.id}`,
            providerType: 'bank',
            name: b.institutionName,
            status: b.status,
            lastSyncedAt: b.lastSyncedAt,
            syncId: b.id,
          })
        })
    }
    if (exchanges) {
      exchanges.forEach(e => {
        list.push({
          id: `exchange-${e.id}`,
          providerType: 'exchange',
          name: e.exchangeType,
          status: e.status,
          lastSyncedAt: e.lastSyncedAt,
          syncId: e.id,
        })
      })
    }
    if (wallets) {
      wallets.forEach(w => {
        list.push({
          id: `wallet-${w.id}`,
          providerType: 'wallet',
          name: w.label || `${w.chain} - ${w.address.slice(0, 8)}...`,
          status: 'CONNECTED',
          lastSyncedAt: w.lastSyncedAt,
          syncId: w.id,
        })
      })
    }
    // Show TR when user has a TR account, regardless of session status
    if (hasTrAccount) {
      const trAccount = accounts?.find(a => a.provider === 'Trade Republic')
      list.push({
        id: 'tr',
        providerType: 'tr',
        name: 'Trade Republic',
        status: trStatus?.isActive ? 'active' : 'SESSION_EXPIRED',
        lastSyncedAt: trAccount?.lastSyncedAt ?? null,
      })
    }
    if (hasBoursoAccount) {
      const boursoAccount = accounts?.find(a => a.provider === 'BoursoBank')
      list.push({
        id: 'bourso',
        providerType: 'bourso',
        name: 'BoursoBank',
        status: boursoStatus?.isActive ? 'active' : 'SESSION_EXPIRED',
        lastSyncedAt: boursoAccount?.lastSyncedAt ?? null,
      })
    }
    if (finaryStatus?.connected) {
      list.push({
        id: 'finary',
        providerType: 'finary',
        name: 'Finary',
        status: finaryStatus.status || 'CONNECTED',
        lastSyncedAt: finaryStatus.lastSyncedAt,
      })
    }
    return list
  }, [banks, exchanges, wallets, hasTrAccount, accounts, trStatus?.isActive, hasBoursoAccount, boursoStatus?.isActive, finaryStatus])

  const handleSync = useCallback((connection: SyncConnection) => {
    // TR without active session: open inline auth instead of syncing
    if (connection.providerType === 'tr' && !trStatus?.isActive) {
      setTrAuthStep('phone')
      return
    }
    // Bourso without active session: open inline auth
    if (connection.providerType === 'bourso' && !boursoStatus?.isActive) {
      setBoursoAuthStep('credentials')
      return
    }

    setSyncingIds(prev => new Set(prev).add(connection.id))

    switch (connection.providerType) {
      case 'bank':
        if (connection.syncId !== undefined) retryBankMutation.mutate(connection.syncId, {
          onSettled: () => setSyncingIds(prev => {
            const next = new Set(prev)
            next.delete(connection.id)
            return next
          }),
        })
        break
      case 'exchange':
        if (connection.syncId !== undefined) syncExchangeMutation.mutate(connection.syncId, {
          onSettled: () => setSyncingIds(prev => {
            const next = new Set(prev)
            next.delete(connection.id)
            return next
          }),
        })
        break
      case 'wallet':
        if (connection.syncId !== undefined) syncWalletMutation.mutate(connection.syncId, {
          onSettled: () => setSyncingIds(prev => {
            const next = new Set(prev)
            next.delete(connection.id)
            return next
          }),
        })
        break
      case 'tr':
        syncTrMutation.mutate(undefined, {
          onSettled: () => setSyncingIds(prev => {
            const next = new Set(prev)
            next.delete(connection.id)
            return next
          }),
        })
        break
      case 'bourso':
        syncBoursoMutation.mutate(undefined, {
          onSettled: () => setSyncingIds(prev => {
            const next = new Set(prev)
            next.delete(connection.id)
            return next
          }),
        })
        break
      case 'finary':
        navigate('/sync?tab=finary')
        onOpenChange(false)
        setSyncingIds(prev => {
          const next = new Set(prev)
          next.delete(connection.id)
          return next
        })
        break
    }
  }, [
    trStatus?.isActive,
    boursoStatus?.isActive,
    retryBankMutation,
    syncExchangeMutation,
    syncWalletMutation,
    syncTrMutation,
    syncBoursoMutation,
    navigate,
    onOpenChange,
  ])

  const handleSyncAll = useCallback(() => {
    // Skip Finary (manual two-phase flow), TR/Bourso without active session
    connections
      .filter(c =>
        c.providerType !== 'finary' &&
        !(c.providerType === 'tr' && !trStatus?.isActive) &&
        !(c.providerType === 'bourso' && !boursoStatus?.isActive)
      )
      .forEach(connection => {
        if (!syncingIds.has(connection.id)) {
          handleSync(connection)
        }
      })
  }, [connections, syncingIds, handleSync, trStatus?.isActive, boursoStatus?.isActive])

  const isSyncAll = syncingIds.size > 0 && connections
    .filter(c =>
      c.providerType !== 'finary' &&
      !(c.providerType === 'tr' && !trStatus?.isActive) &&
      !(c.providerType === 'bourso' && !boursoStatus?.isActive)
    )
    .every(c => syncingIds.has(c.id))

  // --- TR inline auth ---
  function handleTrInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateTrMutation.mutate(
      { phoneNumber: trPhone, pin: trPin },
      {
        onSuccess: (data) => {
          setTrProcessId(data.processId)
          setTrAuthStep('tan')
        },
      },
    )
  }

  function handleTrComplete(e: React.FormEvent) {
    e.preventDefault()
    if (!trProcessId || trTan.length !== TR_VERIFICATION_CODE_LENGTH) return
    completeTrMutation.mutate(
      { processId: trProcessId, tan: trTan },
      {
        onSuccess: () => {
          setTrAuthStep('idle')
          setTrPhone('')
          setTrPin('')
          setTrTan('')
          setTrProcessId(null)
          // Sync runs in background — invalidate to pick up results
          queryClient.invalidateQueries({ queryKey: ['accounts'] })
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
          queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
        },
      },
    )
  }

  function resetTrAuth() {
    setTrAuthStep('idle')
    setTrPhone('')
    setTrPin('')
    setTrTan('')
    setTrProcessId(null)
  }

  // --- BoursoBank inline auth ---
  function handleBoursoInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateBoursoMutation.mutate(
      { customerId: boursoCustomerId, password: boursoPassword },
      {
        onSuccess: (data) => {
          if (!data.mfaRequired) {
            setBoursoAuthStep('idle')
            setBoursoCustomerId('')
            setBoursoPassword('')
            queryClient.invalidateQueries({ queryKey: ['accounts'] })
            queryClient.invalidateQueries({ queryKey: ['dashboard'] })
            queryClient.invalidateQueries({ queryKey: ['sync', 'bourso'] })
          } else {
            setBoursoProcessId(data.processId)
            setBoursoMfaInfo({ type: data.mfaType ?? 'MFA', contact: data.contact ?? '' })
            setBoursoAuthStep('mfa')
          }
        },
      },
    )
  }

  function handleBoursoComplete(e: React.FormEvent) {
    e.preventDefault()
    if (!boursoProcessId) return
    completeBoursoMutation.mutate(
      { processId: boursoProcessId, code: boursoMfaCode },
      {
        onSuccess: () => {
          resetBoursoAuth()
          queryClient.invalidateQueries({ queryKey: ['accounts'] })
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
          queryClient.invalidateQueries({ queryKey: ['sync', 'bourso'] })
        },
      },
    )
  }

  function resetBoursoAuth() {
    setBoursoAuthStep('idle')
    setBoursoCustomerId('')
    setBoursoPassword('')
    setBoursoMfaCode('')
    setBoursoProcessId(null)
    setBoursoMfaInfo(null)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('sync.all.title')}</DialogTitle>
          <DialogDescription>
            {connections.length > 0
              ? t('sync.all.lastSync')
              : t('sync.all.noConnections')}
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Card key={i} size="sm">
                <CardContent className="flex items-center justify-between py-3">
                  <div className="space-y-2">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3 w-24" />
                  </div>
                  <Skeleton className="size-8" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : connections.length === 0 ? (
          <EmptyState
            title={t('sync.all.noConnections')}
            icon={<RefreshCw className="size-12" />}
          />
        ) : (
          <div className="space-y-2">
            {connections.map(connection => {
              const Icon = ProviderIcon[connection.providerType]
              const isSyncing = syncingIds.has(connection.id)
              const isFinary = connection.providerType === 'finary'
              const isTr = connection.providerType === 'tr'
              const isBourso = connection.providerType === 'bourso'

              return (
                <Card key={connection.id} size="sm">
                  <CardContent className="flex flex-col gap-0 py-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <Icon className="size-5 text-muted-foreground" />
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-medium">{connection.name}</span>
                            <Badge variant={statusVariant(connection.status)} className="text-xs">
                              {(isTr || isBourso) && connection.status === 'SESSION_EXPIRED'
                                ? t(isBourso ? 'sync.bourso.noSession' : 'sync.tr.noSession')
                                : connection.status}
                            </Badge>
                            {isTr && (
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <Info className="size-3.5 text-muted-foreground cursor-help" />
                                </TooltipTrigger>
                                <TooltipContent side="top" className="max-w-xs text-xs">
                                  {t('sync.all.trManualInfo')}
                                </TooltipContent>
                              </Tooltip>
                            )}
                          </div>
                          <p className="text-xs text-muted-foreground">
                            {t('sync.all.lastSync')}: {formatTimeAgo(connection.lastSyncedAt)}
                          </p>
                        </div>
                      </div>
                      <Button
                        size="icon-sm"
                        variant="ghost"
                        disabled={isSyncing}
                        onClick={() => handleSync(connection)}
                        title={isFinary ? t('sync.all.openFinary') : undefined}
                      >
                        {isSyncing ? (
                          <Loader2 className="size-4 animate-spin" />
                        ) : isFinary ? (
                          <ExternalLink className="size-4" />
                        ) : (
                          <RefreshCw className="size-4" />
                        )}
                      </Button>
                    </div>

                    {/* BoursoBank inline auth form */}
                    {isBourso && boursoAuthStep !== 'idle' && !boursoStatus?.isActive && (
                      <div className="mt-3 border-t pt-3">
                        {boursoAuthStep === 'credentials' && (
                          <form onSubmit={handleBoursoInitiate} className="space-y-3">
                            <div className="space-y-1">
                              <Label htmlFor="bourso-modal-id">
                                <User className="size-3 inline-block mr-1" />
                                {t('sync.bourso.customerId')}
                              </Label>
                              <Input
                                id="bourso-modal-id"
                                type="text"
                                inputMode="numeric"
                                value={boursoCustomerId}
                                onChange={e => setBoursoCustomerId(e.target.value)}
                                required
                              />
                            </div>
                            <div className="space-y-1">
                              <Label htmlFor="bourso-modal-pwd">
                                <Lock className="size-3 inline-block mr-1" />
                                {t('sync.bourso.password')}
                              </Label>
                              <Input
                                id="bourso-modal-pwd"
                                type="password"
                                inputMode="numeric"
                                value={boursoPassword}
                                onChange={e => setBoursoPassword(e.target.value)}
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={initiateBoursoMutation.isPending}>
                                {initiateBoursoMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.bourso.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetBoursoAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                        {boursoAuthStep === 'mfa' && (
                          <form onSubmit={handleBoursoComplete} className="space-y-3">
                            {boursoMfaInfo && (
                              <p className="text-xs text-muted-foreground">
                                {t('sync.bourso.mfaPrompt', { mfaType: boursoMfaInfo.type, contact: boursoMfaInfo.contact })}
                              </p>
                            )}
                            <div className="space-y-1">
                              <Label htmlFor="bourso-modal-mfa">
                                <ShieldCheck className="size-3 inline-block mr-1" />
                                {t('sync.bourso.mfaCode')}
                              </Label>
                              <Input
                                id="bourso-modal-mfa"
                                type="text"
                                inputMode="numeric"
                                autoComplete="one-time-code"
                                value={boursoMfaCode}
                                onChange={e => setBoursoMfaCode(e.target.value)}
                                autoFocus
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={completeBoursoMutation.isPending}>
                                {completeBoursoMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.bourso.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetBoursoAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                      </div>
                    )}

                    {/* TR inline auth form */}
                    {isTr && trAuthStep !== 'idle' && !trStatus?.isActive && (
                      <div className="mt-3 border-t pt-3">
                        <p className="mb-3 text-xs text-muted-foreground">
                          {t('sync.all.trSlowWarning')}
                        </p>
                        {trAuthStep === 'phone' && (
                          <form onSubmit={handleTrInitiate} className="space-y-3">
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-phone">
                                <Smartphone className="size-3 inline-block mr-1" />
                                {t('sync.tr.phone')}
                              </Label>
                              <Input
                                id="tr-modal-phone"
                                type="tel"
                                value={trPhone}
                                onChange={e => setTrPhone(e.target.value)}
                                placeholder="+49..."
                                required
                              />
                            </div>
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-pin">
                                <Lock className="size-3 inline-block mr-1" />
                                {t('sync.tr.pin')}
                              </Label>
                              <Input
                                id="tr-modal-pin"
                                type="password"
                                value={trPin}
                                onChange={e => setTrPin(e.target.value)}
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={initiateTrMutation.isPending}>
                                {initiateTrMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.tr.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetTrAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                        {trAuthStep === 'tan' && (
                          <form onSubmit={handleTrComplete} className="space-y-3">
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-tan">
                                <ShieldCheck className="size-3 inline-block mr-1" />
                                {t('sync.tr.tan')}
                              </Label>
                              <Input
                                id="tr-modal-tan"
                                value={trTan}
                                onChange={e => setTrTan(e.target.value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
                                inputMode="numeric"
                                autoComplete="one-time-code"
                                maxLength={TR_VERIFICATION_CODE_LENGTH}
                                autoFocus
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={completeTrMutation.isPending || trTan.length !== TR_VERIFICATION_CODE_LENGTH}>
                                {completeTrMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.tr.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetTrAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}

        {connections.length > 0 && (
          <DialogFooter>
            <Button
              onClick={handleSyncAll}
              disabled={isSyncAll || isLoading}
            >
              {isSyncAll ? (
                <>
                  <Loader2 className="mr-2 size-4 animate-spin" />
                  {t('sync.all.syncing')}
                </>
              ) : (
                <>
                  <RefreshCw className="mr-2 size-4" />
                  {t('sync.all.syncAll')}
                </>
              )}
            </Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
