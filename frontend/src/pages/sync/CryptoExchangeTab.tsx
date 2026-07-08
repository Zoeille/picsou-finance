import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import { formatDate } from '@/lib/utils'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  RefreshCw,
  Trash2,
  Eye,
  EyeOff,
} from 'lucide-react'
import type { ExchangeStatus, ExchangeType } from '@/types/api'
import { extractErrorMessage } from '@/lib/errors'

function useExchanges() {
  return useQuery<ExchangeStatus[]>({
    queryKey: ['crypto', 'exchanges'],
    queryFn: () => api.get('/crypto/exchange/status').then(r => r.data),
    refetchInterval: 60_000,
  })
}

function useAddExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { type: ExchangeType; apiKey: string; apiSecret: string }) =>
      api.post('/crypto/exchange', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['crypto', 'exchanges'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

function useSyncExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post(`/crypto/exchange/${id}/sync`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['crypto', 'exchanges'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

function useRemoveExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/crypto/exchange/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['crypto', 'exchanges'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function CryptoExchangeTab() {
  const { t } = useTranslation()
  const { data: exchanges, isLoading, error, refetch } = useExchanges()
  const addMutation = useAddExchange()
  const syncMutation = useSyncExchange()
  const removeMutation = useRemoveExchange()

  const [showAddForm, setShowAddForm] = useState(false)
  const [exchangeType, setExchangeType] = useState<ExchangeType>('BINANCE')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [removingId, setRemovingId] = useState<number | null>(null)

  function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    addMutation.mutate(
      { type: exchangeType, apiKey, apiSecret },
      {
        onSuccess: () => {
          setApiKey('')
          setApiSecret('')
          setShowSecret(false)
          setShowAddForm(false)
        },
      },
    )
  }

  function handleRemove() {
    if (removingId == null) return
    removeMutation.mutate(removingId, {
      onSuccess: () => setRemovingId(null),
    })
  }

  if (isLoading) {
    return (
      <div className="space-y-4">
        {Array.from({ length: 2 }).map((_, i) => (
          <Card key={i} size="sm">
            <CardContent className="flex items-center justify-between p-4">
              <div className="space-y-2">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-3 w-40" />
              </div>
              <div className="flex gap-2">
                <Skeleton className="size-8" />
                <Skeleton className="size-8" />
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <p className="text-sm text-muted-foreground">{extractErrorMessage(error)}</p>
        <Button variant="outline" onClick={() => refetch()} className="mt-4">
          {t('common.retry')}
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Add exchange */}
      {!showAddForm ? (
        <Button onClick={() => setShowAddForm(true)}>
          <Plus />
          {t('sync.exchanges.add')}
        </Button>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 p-4">
            <form onSubmit={handleAdd} className="space-y-4">
              <div className="space-y-2">
                <Label>{t('sync.exchanges.type')}</Label>
                <div className="flex gap-2">
                  {(['BINANCE', 'KRAKEN'] as ExchangeType[]).map(type => (
                    <Button
                      key={type}
                      type="button"
                      variant={exchangeType === type ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => setExchangeType(type)}
                    >
                      {type}
                    </Button>
                  ))}
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="exchange-api-key">{t('sync.exchanges.apiKey')}</Label>
                <Input
                  id="exchange-api-key"
                  value={apiKey}
                  onChange={e => setApiKey(e.target.value)}
                  placeholder={t('sync.exchanges.apiKey')}
                  required
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="exchange-api-secret">{t('sync.exchanges.apiSecret')}</Label>
                <div className="relative">
                  <Input
                    id="exchange-api-secret"
                    type={showSecret ? 'text' : 'password'}
                    value={apiSecret}
                    onChange={e => setApiSecret(e.target.value)}
                    placeholder={t('sync.exchanges.apiSecret')}
                    required
                    className="pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowSecret(prev => !prev)}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showSecret ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
              </div>

              <div className="flex gap-2">
                <Button type="submit" disabled={addMutation.isPending}>
                  {t('sync.exchanges.connect')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setShowAddForm(false)
                    setApiKey('')
                    setApiSecret('')
                    setShowSecret(false)
                  }}
                >
                  {t('common.cancel')}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {/* Exchange list */}
      {!exchanges || exchanges.length === 0 ? (
        <EmptyState
          title={t('sync.exchanges.noExchanges')}
          action={
            showAddForm
              ? undefined
              : {
                  label: t('sync.exchanges.add'),
                  onClick: () => setShowAddForm(true),
                }
          }
        />
      ) : (
        <div className="space-y-3">
          {exchanges.map(exchange => (
            <Card key={exchange.id} size="sm">
              <CardContent className="flex items-center justify-between p-4">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{exchange.exchangeType}</span>
                    <Badge variant="secondary">{exchange.exchangeType}</Badge>
                    <Badge
                      variant={exchange.status === 'CONNECTED' ? 'default' : 'destructive'}
                    >
                      {exchange.status}
                    </Badge>
                  </div>
                  {exchange.lastSyncedAt && (
                    <p className="text-xs text-muted-foreground">
                      {t('sync.exchanges.lastSync')}: {formatDate(exchange.lastSyncedAt)}
                    </p>
                  )}
                </div>

                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => syncMutation.mutate(exchange.id)}
                    disabled={syncMutation.isPending}
                  >
                    <RefreshCw />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setRemovingId(exchange.id)}
                  >
                    <Trash2 />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Remove confirmation */}
      <ConfirmDialog
        open={removingId != null}
        onOpenChange={open => !open && setRemovingId(null)}
        title={t('sync.exchanges.remove')}
        description={t('sync.exchanges.removeConfirm')}
        onConfirm={handleRemove}
        loading={removeMutation.isPending}
      />
    </div>
  )
}
