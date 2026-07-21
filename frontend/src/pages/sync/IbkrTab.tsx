import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { api } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { RefreshCw, LogOut, KeyRound, Hash, AlertTriangle, Loader2 } from 'lucide-react'
import type { Account, IbkrConnectionStatus } from '@/types/api'
import { extractErrorMessage } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

export function IbkrTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const [token, setToken] = useState('')
  const [queryId, setQueryId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showDisconnectConfirm, setShowDisconnectConfirm] = useState(false)

  const { data: status, isLoading: statusLoading } = useQuery<IbkrConnectionStatus>({
    queryKey: ['sync', 'ibkr', 'status'],
    queryFn: () => api.get<IbkrConnectionStatus>('/ibkr/status').then((r) => r.data),
  })

  const isConnected = status?.connected ?? false
  const isError = status?.status === 'ERROR'

  const connectMutation = useMutation({
    mutationFn: (params: { token: string; queryId: string }) =>
      api.post('/ibkr/connect', params).then((r) => r.data),
    onSuccess: () => {
      setToken('')
      setQueryId('')
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['sync', 'ibkr', 'status'] })
    },
    onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.ibkr.errors.connectFailed'))),
  })

  const syncMutation = useMutation({
    mutationFn: () => api.post<Account[]>('/ibkr/sync').then((r) => r.data),
    onSuccess: (accounts) => {
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['sync', 'ibkr', 'status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      toast.success(t('sync.ibkr.syncedToast', { count: accounts.length }))
    },
    onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.ibkr.errors.syncFailed'))),
  })

  const disconnectMutation = useMutation({
    mutationFn: () => api.delete('/ibkr/connection').then((r) => r.data),
    onSuccess: () => {
      setShowDisconnectConfirm(false)
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['sync', 'ibkr', 'status'] })
    },
    onError: (err: unknown) => {
      // Close the dialog so the page-level error banner is actually visible.
      setShowDisconnectConfirm(false)
      setError(extractErrorMessage(err, t('sync.ibkr.errors.disconnectFailed')))
    },
  })

  function handleConnect(e: React.FormEvent) {
    e.preventDefault()
    if (!token.trim() || !queryId.trim()) return
    connectMutation.mutate({ token: token.trim(), queryId: queryId.trim() })
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Connection status card */}
      {isConnected && status && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex flex-wrap items-center gap-3">
              {isError ? (
                <Badge className="bg-destructive/10 text-destructive">{t('sync.ibkr.statusError')}</Badge>
              ) : (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.ibkr.connected')}
                </Badge>
              )}
              {status.maskedToken && (
                <span className="text-sm text-muted-foreground">{status.maskedToken}</span>
              )}
              {status.lastSyncedAt ? (
                <span className="text-xs text-muted-foreground">
                  {t('sync.ibkr.lastSync')}: {formatDateTime(status.lastSyncedAt)}
                </span>
              ) : (
                <span className="text-xs text-muted-foreground">{t('sync.ibkr.neverSynced')}</span>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Error banner */}
      {error && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{error}</p>
          </CardContent>
        </Card>
      )}

      {isConnected ? (
        /* Connected: sync + disconnect */
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            {syncMutation.isPending ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                {t('sync.ibkr.syncing')}
              </>
            ) : (
              <>
                <RefreshCw className="size-4" />
                {t('sync.ibkr.sync')}
              </>
            )}
          </Button>
          <Button
            variant="destructive"
            onClick={() => setShowDisconnectConfirm(true)}
            disabled={disconnectMutation.isPending}
          >
            <LogOut className="size-4" />
            {t('sync.ibkr.disconnect')}
          </Button>
        </div>
      ) : (
        /* Not connected: token + query id form */
        <form onSubmit={handleConnect} className="mx-auto max-w-lg space-y-4">
          <p className="text-sm text-muted-foreground">{t('sync.ibkr.setupHint')}</p>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="ibkr-token">
                  <KeyRound className="mr-1 inline-block size-4" />
                  {t('sync.ibkr.token')}
                </Label>
                <Input
                  id="ibkr-token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder={t('sync.ibkr.tokenPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="ibkr-query">
                  <Hash className="mr-1 inline-block size-4" />
                  {t('sync.ibkr.queryId')}
                </Label>
                <Input
                  id="ibkr-query"
                  value={queryId}
                  onChange={(e) => setQueryId(e.target.value)}
                  placeholder={t('sync.ibkr.queryIdPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={connectMutation.isPending || !token.trim() || !queryId.trim()}
                className="w-full"
              >
                {connectMutation.isPending && <Loader2 className="size-4 animate-spin" />}
                {t('sync.ibkr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      <ConfirmDialog
        open={showDisconnectConfirm}
        onOpenChange={setShowDisconnectConfirm}
        title={t('sync.ibkr.disconnect')}
        description={t('sync.ibkr.disconnectConfirm')}
        onConfirm={() => disconnectMutation.mutate()}
        loading={disconnectMutation.isPending}
      />
    </div>
  )
}
