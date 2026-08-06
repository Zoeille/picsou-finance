import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  useDegiroSessionStatus,
  useInitiateDegiroAuth,
  useCompleteDegiroAuth,
  useSyncDegiro,
  useClearDegiroSession,
} from '@/features/sync/hooks'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, User, Lock, ShieldCheck, AlertTriangle } from 'lucide-react'
import type { DegiroAuthInitResponse } from '@/types/api'
import { extractErrorMessage, getErrorStatus, getErrorDetail } from '@/lib/errors'
import { formatDate } from '@/lib/utils'

type AuthState = 'IDLE' | 'AWAITING_TOTP' | 'CONNECTED' | 'ERROR'

export function DegiroTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const [authState, setAuthState] = useState<AuthState>('IDLE')
  const [username, setUsername]   = useState('')
  const [password, setPassword]   = useState('')
  const [totpCode, setTotpCode]   = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg]   = useState<string | null>(null)

  const {
    data: sessionStatus,
    isLoading: statusLoading,
    isError: statusIsError,
    error: statusError,
  } = useDegiroSessionStatus()
  const initiateMutation = useInitiateDegiroAuth()
  const completeMutation = useCompleteDegiroAuth()
  const syncMutation     = useSyncDegiro()
  const clearMutation    = useClearDegiroSession()

  const needsReauth = sessionStatus?.status === 'REAUTH_REQUIRED'
  const effectiveState: AuthState =
    sessionStatus?.isActive && !needsReauth ? 'CONNECTED' : authState

  function formatError(error: unknown): string {
    const status = getErrorStatus(error)
    if (status === 429) return t('sync.degiro.errors.tooManyAttempts')
    if (status === 401) return t('sync.degiro.errors.invalidCredentials')
    const detail = (getErrorDetail(error) ?? '').toLowerCase()
    if (detail.includes('expired') || detail.includes('reconnect'))
      return t('sync.degiro.errors.sessionExpired')
    if (detail.includes('totp') || detail.includes('code'))
      return t('sync.degiro.errors.invalidTotpCode')
    if (status && status >= 500) return t('sync.degiro.errors.serverError')
    return extractErrorMessage(error, t('sync.degiro.errors.serverError'))
  }

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    setErrorMsg(null)
    initiateMutation.mutate(
      { username, password },
      {
        onSuccess: (data: DegiroAuthInitResponse) => {
          if (!data.totpRequired) {
            queryClient.invalidateQueries({ queryKey: ['sync', 'degiro'] })
            queryClient.invalidateQueries({ queryKey: ['accounts'] })
            queryClient.invalidateQueries({ queryKey: ['dashboard'] })
            setAuthState('IDLE')
            setUsername('')
            setPassword('')
          } else if (!data.processId) {
            // Shouldn't happen — the adapter rejects a blank processId before reporting
            // totpRequired. Guarded anyway: without it a malformed response strands the
            // user on a TOTP prompt whose submit handler silently returns.
            setErrorMsg(t('sync.degiro.errors.serverError'))
            setAuthState('ERROR')
          } else {
            setProcessId(data.processId)
            setAuthState('AWAITING_TOTP')
          }
        },
        onError: (error: unknown) => {
          setErrorMsg(formatError(error))
          setAuthState('ERROR')
        },
      },
    )
  }

  function handleTotp(e: React.FormEvent) {
    e.preventDefault()
    if (!processId) return
    setErrorMsg(null)
    completeMutation.mutate(
      { processId, code: totpCode },
      {
        onSuccess: () => {
          setAuthState('IDLE')
          setTotpCode('')
          setProcessId(null)
          setUsername('')
          setPassword('')
        },
        onError: (error: unknown) => {
          setErrorMsg(formatError(error))
          setAuthState('ERROR')
        },
      },
    )
  }

  function handleRetry() {
    setErrorMsg(null)
    setAuthState('IDLE')
    setProcessId(null)
    setTotpCode('')
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  // A failed status query would otherwise leave the tab rendering stale session state
  // with no indication anything went wrong, so it feeds the same panel as action errors.
  const visibleError = errorMsg ?? (statusIsError ? formatError(statusError) : null)

  return (
    <div className="space-y-6">
      {/* Session status */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center gap-3">
            {effectiveState === 'CONNECTED' ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t('sync.degiro.sessionActive')}
              </Badge>
            ) : needsReauth ? (
              <Badge variant="outline" className="border-amber-500/50 text-amber-600 dark:text-amber-400">
                {t('sync.degiro.reauthRequired')}
              </Badge>
            ) : (
              <Badge variant="outline">{t('sync.degiro.noSession')}</Badge>
            )}
            {sessionStatus?.lastSyncedAt && (
              <span className="text-sm text-muted-foreground">
                {t('sync.degiro.lastSyncedAt')} {formatDate(sessionStatus.lastSyncedAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Error */}
      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{visibleError}</p>
              <Button size="sm" variant="outline" onClick={handleRetry}>
                {t('common.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Connected (and not needing reauth) */}
      {effectiveState === 'CONNECTED' && (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => {
              setErrorMsg(null)
              syncMutation.mutate(undefined, { onError: error => setErrorMsg(formatError(error)) })
            }}
            disabled={syncMutation.isPending}
          >
            <RefreshCw />
            {t('sync.degiro.sync')}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setErrorMsg(null)
              clearMutation.mutate(undefined, { onError: error => setErrorMsg(formatError(error)) })
            }}
            disabled={clearMutation.isPending}
          >
            <LogOut />
            {t('sync.degiro.clearSession')}
          </Button>
        </div>
      )}

      {/* IDLE or REAUTH_REQUIRED: login form */}
      {(effectiveState === 'IDLE' || needsReauth) && authState !== 'AWAITING_TOTP' && authState !== 'ERROR' && (
        <form onSubmit={handleInitiate} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="degiro-username">
                  <User className="size-4 inline-block mr-1" />
                  {t('sync.degiro.username')}
                </Label>
                <Input
                  id="degiro-username"
                  type="text"
                  autoComplete="username"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="degiro-password">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.degiro.password')}
                </Label>
                <Input
                  id="degiro-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={initiateMutation.isPending}>
                {t('sync.degiro.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {/* AWAITING_TOTP: authenticator code */}
      {authState === 'AWAITING_TOTP' && (
        <form onSubmit={handleTotp} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <p className="text-sm text-muted-foreground">{t('sync.degiro.totpPrompt')}</p>
              <div className="space-y-2">
                <Label htmlFor="degiro-totp">
                  <ShieldCheck className="size-4 inline-block mr-1" />
                  {t('sync.degiro.totpCode')}
                </Label>
                <Input
                  id="degiro-totp"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={totpCode}
                  onChange={e => setTotpCode(e.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={completeMutation.isPending}>
                {t('sync.degiro.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
