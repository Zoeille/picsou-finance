import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cryptoExchangeApi, type ExchangeType, type ExchangeStatus, type Account } from '../../lib/api'
import { GlassCard, PageHeader } from '../../components/shared'
import { RefreshCw, Trash2, Loader2, CheckCircle2, AlertCircle, Eye, EyeOff } from 'lucide-react'

const EXCHANGES: { value: ExchangeType; label: string }[] = [
  { value: 'BINANCE', label: 'Binance' },
  { value: 'KRAKEN', label: 'Kraken' },
]

function formatEur(amount: number) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(amount)
}

export function CryptoExchangeSyncPage() {
  const queryClient = useQueryClient()
  const [exchangeType, setExchangeType] = useState<ExchangeType>('BINANCE')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [syncedAccount, setSyncedAccount] = useState<Account | null>(null)

  const { data: exchanges, isLoading } = useQuery({
    queryKey: ['crypto-exchange-status'],
    queryFn: cryptoExchangeApi.status,
    refetchInterval: 60_000,
  })

  const addMutation = useMutation({
    mutationFn: () => cryptoExchangeApi.add(exchangeType, apiKey, apiSecret),
    onSuccess: (account) => {
      setApiKey('')
      setApiSecret('')
      setSyncedAccount(account)
      queryClient.invalidateQueries({ queryKey: ['crypto-exchange-status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const syncMutation = useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.sync(id),
    onSuccess: (account) => {
      setSyncedAccount(account)
      queryClient.invalidateQueries({ queryKey: ['crypto-exchange-status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.remove(id),
    onSuccess: () => {
      setSyncedAccount(null)
      queryClient.invalidateQueries({ queryKey: ['crypto-exchange-status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  return (
    <div className="flex flex-col gap-6 max-w-2xl">
      <PageHeader title="Exchanges Crypto" surtitle="Connexion aux exchanges centralisés" />

      {/* Connected exchanges */}
      {exchanges && exchanges.length > 0 && (
        <GlassCard padding={false} className="p-6 flex flex-col gap-4">
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Exchanges connectés</h2>
          <div className="flex flex-col gap-2">
            {exchanges.map((ex: ExchangeStatus) => (
              <div key={ex.id} className="flex items-center justify-between py-2.5 px-3 bg-gray-50 rounded-[8px]">
                <div className="flex items-center gap-3">
                  <span className="text-gray-800 font-medium" style={{ fontSize: 13 }}>{ex.exchangeType}</span>
                  {ex.status === 'CONNECTED' ? (
                    <span className="flex items-center gap-1 text-emerald-600" style={{ fontSize: 11 }}>
                      <CheckCircle2 size={12} /> Connecté
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-red-500" style={{ fontSize: 11 }}>
                      <AlertCircle size={12} /> Erreur
                    </span>
                  )}
                  {ex.lastSyncedAt && (
                    <span className="text-gray-400" style={{ fontSize: 11 }}>
                      Sync : {new Date(ex.lastSyncedAt).toLocaleString('fr-FR')}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    onClick={() => syncMutation.mutate(ex.id)}
                    disabled={syncMutation.isPending}
                    className="p-1.5 text-gray-400 hover:text-gray-700 transition-colors"
                    title="Synchroniser"
                  >
                    {syncMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                  </button>
                  <button
                    onClick={() => removeMutation.mutate(ex.id)}
                    disabled={removeMutation.isPending}
                    className="p-1.5 text-gray-400 hover:text-red-500 transition-colors"
                    title="Supprimer"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>

          {syncMutation.isSuccess && syncedAccount && (
            <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
              <CheckCircle2 size={14} /> Synchronisé — {formatEur(syncedAccount.currentBalanceEur)}
            </p>
          )}
        </GlassCard>
      )}

      {/* Add exchange */}
      <GlassCard padding={false} className="p-6 flex flex-col gap-5">
        <div>
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Connecter un exchange</h2>
          <p className="text-gray-500 mt-1" style={{ fontSize: 13 }}>
            Utilisez des clés API en <span className="font-medium text-gray-700">lecture seule</span>. Ne donnez jamais les permissions de trading.
          </p>
        </div>

        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>Exchange</label>
            <select
              value={exchangeType}
              onChange={e => setExchangeType(e.target.value as ExchangeType)}
              className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm bg-white focus:outline-none focus:ring-2 focus:ring-gray-900/20"
            >
              {EXCHANGES.map(ex => (
                <option key={ex.value} value={ex.value}>{ex.label}</option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>API Key</label>
            <input
              type="text"
              placeholder="Votre clé API"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm font-mono focus:outline-none focus:ring-2 focus:ring-gray-900/20"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>API Secret</label>
            <div className="relative">
              <input
                type={showSecret ? 'text' : 'password'}
                placeholder="Votre secret API"
                value={apiSecret}
                onChange={e => setApiSecret(e.target.value)}
                className="w-full px-3 py-2 pr-10 border border-gray-200 rounded-[10px] text-sm font-mono focus:outline-none focus:ring-2 focus:ring-gray-900/20"
              />
              <button
                type="button"
                onClick={() => setShowSecret(!showSecret)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                {showSecret ? <EyeOff size={14} /> : <Eye size={14} />}
              </button>
            </div>
          </div>

          <button
            onClick={() => addMutation.mutate()}
            disabled={!apiKey || !apiSecret || addMutation.isPending}
            className="self-start flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
          >
            {addMutation.isPending && <Loader2 size={14} className="animate-spin" />}
            Connecter
          </button>

          {addMutation.isSuccess && syncedAccount && (
            <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
              <CheckCircle2 size={14} /> Connecté — {formatEur(syncedAccount.currentBalanceEur)}
            </p>
          )}
          {addMutation.isError && (
            <p className="text-red-500" style={{ fontSize: 13 }}>
              {(addMutation.error as Error).message}
            </p>
          )}
        </div>
      </GlassCard>

      {isLoading && (
        <div className="flex justify-center py-4">
          <Loader2 size={20} className="text-gray-400 animate-spin" />
        </div>
      )}
    </div>
  )
}
