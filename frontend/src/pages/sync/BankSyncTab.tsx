import { useState, useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import {
  Search,
  RefreshCw,
  Trash2,
  Landmark,
  Loader2,
  CheckCircle,
  AlertTriangle,
} from 'lucide-react'
import type { Institution } from '@/types/api'
import { extractErrorMessage } from '@/lib/errors'

type CallbackStatus = 'completing' | 'done' | 'error'

interface BankConnection {
  id: number
  institutionId: string
  institutionName: string
  status: string
  lastSyncedAt: string | null
}

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'LINKED': return 'default'
    case 'CREATED': return 'secondary'
    case 'EXPIRED': return 'outline'
    case 'FAILED': return 'destructive'
    default: return 'outline'
  }
}

function statusClasses(status: string): string {
  switch (status) {
    case 'LINKED': return 'bg-green-500/10 text-green-600 dark:text-green-400'
    case 'CREATED': return 'bg-amber-500/10 text-amber-600 dark:text-amber-400'
    case 'EXPIRED': return 'bg-muted text-muted-foreground'
    case 'FAILED': return 'bg-red-500/10 text-red-600 dark:text-red-400'
    default: return ''
  }
}

function cleanBankCallbackUrl() {
  if (window.location.pathname.endsWith('/sync/callback')) {
    window.history.replaceState(window.history.state, '', '/sync?tab=banks')
  }
}

export function BankSyncTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()

  const [searchQuery, setSearchQuery] = useState('')
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [callbackStatus, setCallbackStatus] = useState<CallbackStatus | null>(null)
  const [callbackError, setCallbackError] = useState<string | null>(null)
  const [initiateError, setInitiateError] = useState<string | null>(null)
  const handledCode = useRef<string | null>(null)

  const { mutate: completeSync } = useMutation({
    mutationFn: (code: string) => api.get('/sync/complete', { params: { code } }).then(r => r.data),
    onSuccess: () => {
      setCallbackStatus('done')
      cleanBankCallbackUrl()
      queryClient.invalidateQueries({ queryKey: ['sync', 'connections'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (err: unknown) => {
      setCallbackStatus('error')
      setCallbackError(extractErrorMessage(err))
    },
  })

  useEffect(() => {
    const code = searchParams.get('code')
    if (code && code !== handledCode.current) {
      handledCode.current = code
      setCallbackStatus('completing')
      completeSync(code)
    }
  }, [searchParams, completeSync])

  const searchEnabled = searchQuery.trim().length >= 2

  const { data: institutions, isLoading: searchLoading } = useQuery<Institution[]>({
    queryKey: ['sync', 'institutions', searchQuery],
    queryFn: () =>
      api
        .get<Institution[]>('/sync/institutions', { params: { query: searchQuery.trim() } })
        .then(r => r.data),
    enabled: searchEnabled,
  })

  const { data: connections, isLoading: connectionsLoading } = useQuery<BankConnection[]>({
    queryKey: ['sync', 'connections'],
    queryFn: () => api.get<BankConnection[]>('/sync/status').then(r => r.data),
    refetchInterval: 30_000,
  })

  const initiateMutation = useMutation({
    mutationFn: (params: { institutionId: string; institutionName: string }) =>
      api.post<{ authLink: string }>('/sync/initiate', params).then(r => r.data),
    onSuccess: (data) => {
      setInitiateError(null)
      window.location.href = data.authLink
    },
    onError: (err: unknown) => {
      setInitiateError(extractErrorMessage(err, t('sync.banks.initiateError')))
    },
  })

  const retryMutation = useMutation({
    mutationFn: (id: number) =>
      api.post(`/sync/${id}/retry`).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'connections'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) =>
      api.delete(`/sync/${id}`).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'connections'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      setDeleteId(null)
    },
  })

  function handleDelete() {
    if (deleteId !== null) {
      deleteMutation.mutate(deleteId)
    }
  }

  const connectionToBeDeleted = connections?.find(c => c.id === deleteId)

  return (
    <div className="space-y-6">
      {/* OAuth callback status */}
      {callbackStatus && (
        <Card className={
          callbackStatus === 'done' ? 'border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950' :
          callbackStatus === 'error' ? 'border-destructive/30 bg-destructive/5' :
          'border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-950'
        }>
          <CardContent className="flex items-center gap-3 py-3">
            {callbackStatus === 'completing' && (
              <Loader2 className="size-4 animate-spin text-blue-600 dark:text-blue-400" />
            )}
            {callbackStatus === 'done' && (
              <CheckCircle className="size-4 text-green-600 dark:text-green-400" />
            )}
            {callbackStatus === 'error' && (
              <AlertTriangle className="size-4 text-destructive" />
            )}
            <span className="text-sm font-medium">
              {callbackStatus === 'completing' && t('sync.banks.callbackCompleting')}
              {callbackStatus === 'done' && t('sync.banks.callbackDone')}
              {callbackStatus === 'error' && `${t('sync.banks.callbackError')}${callbackError ? `: ${callbackError}` : ''}`}
            </span>
          </CardContent>
        </Card>
      )}

      {/* Initiate error */}
      {initiateError && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="flex items-center gap-3 py-3">
            <AlertTriangle className="size-4 shrink-0 text-destructive" />
            <span className="flex-1 text-sm font-medium text-destructive">{initiateError}</span>
            <Button variant="ghost" size="sm" onClick={() => setInitiateError(null)}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Search section */}
      <div className="space-y-3">
        <label className="text-sm font-medium">{t('sync.banks.search')}</label>
        <div className="relative">
          <Search
            className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground"
          />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('sync.banks.searchPlaceholder')}
            className="pl-10"
          />
        </div>

        {/* Search results */}
        {searchLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

        {searchEnabled && institutions && institutions.length > 0 && (
          <div className="space-y-2">
            {institutions.map(inst => (
              <Card key={inst.id} size="sm">
                <CardContent className="grid grid-cols-[minmax(0,1fr)_2.5rem_auto] items-center gap-4 py-2">
                  <div className="flex min-w-0 items-center gap-3">
                    <Landmark className="size-5 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 text-sm font-medium leading-5">{inst.name}</span>
                  </div>
                  <span className="justify-self-center text-xs text-muted-foreground">{inst.country}</span>
                  <Button
                    size="sm"
                    className="justify-self-end"
                    onClick={() =>
                      initiateMutation.mutate({ institutionId: inst.id, institutionName: inst.name })
                    }
                    disabled={initiateMutation.isPending}
                  >
                    {t('sync.banks.connect')}
                  </Button>
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        {searchEnabled && institutions && institutions.length === 0 && !searchLoading && (
          <p className="text-sm text-muted-foreground">{t('sync.banks.noConnections')}</p>
        )}
      </div>

      {/* Active connections */}
      <div className="space-y-3">
        <h3 className="text-sm font-medium">{t('sync.banks.connected')}</h3>

        {connectionsLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

        {!connectionsLoading && (!connections || connections.length === 0) && (
          <EmptyState
            title={t('sync.banks.noConnections')}
            icon={<Landmark className="size-12" />}
          />
        )}

        {!connectionsLoading && connections && connections.length > 0 && (
          <div className="space-y-3">
            <p className="text-xs text-muted-foreground">
              {t('sync.banks.psd2ScopeNote')}
            </p>
            {connections.map(conn => (
              <Card key={conn.id} size="sm">
                <CardContent className="flex items-center justify-between py-2">
                  <div className="flex items-center gap-3">
                    <span className="text-sm font-medium">{conn.institutionName}</span>
                    <Badge
                      variant={statusVariant(conn.status)}
                      className={statusClasses(conn.status)}
                    >
                      {conn.status}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-2">
                    {conn.status === 'FAILED' && (
                      <Button
                        size="icon-sm"
                        variant="ghost"
                        onClick={() => retryMutation.mutate(conn.id)}
                        disabled={retryMutation.isPending}
                      >
                        <RefreshCw className="size-4" />
                      </Button>
                    )}
                    <Button
                      size="icon-sm"
                      variant="ghost"
                      onClick={() => setDeleteId(conn.id)}
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>

      {/* Delete confirmation */}
      <ConfirmDialog
        open={deleteId !== null}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('sync.banks.delete')}
        description={connectionToBeDeleted ? t('sync.banks.deleteConfirm') : ''}
        onConfirm={handleDelete}
        loading={deleteMutation.isPending}
      />
    </div>
  )
}
