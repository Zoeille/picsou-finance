import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cryptoWalletApi, type ChainType, type WalletStatus, type Account } from '../../lib/api'
import { GlassCard, PageHeader } from '../../components/shared'
import { RefreshCw, Trash2, Loader2, CheckCircle2, Plus } from 'lucide-react'

const CHAINS: { value: ChainType; label: string; placeholder: string }[] = [
  { value: 'SOLANA', label: 'Solana', placeholder: '7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU' },
  { value: 'ETHEREUM', label: 'Ethereum', placeholder: '0x742d35Cc6634C0532925a3b844Bc9e7595f2bD18' },
  { value: 'BITCOIN', label: 'Bitcoin', placeholder: 'bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh' },
]

function formatEur(amount: number) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(amount)
}

function shortenAddress(address: string) {
  if (address.length <= 16) return address
  return address.slice(0, 8) + '…' + address.slice(-6)
}

export function WalletSyncPage() {
  const queryClient = useQueryClient()
  const [chain, setChain] = useState<ChainType>('SOLANA')
  const [address, setAddress] = useState('')
  const [label, setLabel] = useState('')
  const [syncedAccount, setSyncedAccount] = useState<Account | null>(null)

  const { data: wallets, isLoading } = useQuery({
    queryKey: ['crypto-wallet-list'],
    queryFn: cryptoWalletApi.list,
    refetchInterval: 60_000,
  })

  const addMutation = useMutation({
    mutationFn: () => cryptoWalletApi.add(chain, address, label || undefined),
    onSuccess: (account) => {
      setAddress('')
      setLabel('')
      setSyncedAccount(account)
      queryClient.invalidateQueries({ queryKey: ['crypto-wallet-list'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const syncMutation = useMutation({
    mutationFn: (id: number) => cryptoWalletApi.sync(id),
    onSuccess: (account) => {
      setSyncedAccount(account)
      queryClient.invalidateQueries({ queryKey: ['crypto-wallet-list'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (id: number) => cryptoWalletApi.remove(id),
    onSuccess: () => {
      setSyncedAccount(null)
      queryClient.invalidateQueries({ queryKey: ['crypto-wallet-list'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const selectedChain = CHAINS.find(c => c.value === chain)!

  return (
    <div className="flex flex-col gap-6 max-w-2xl">
      <PageHeader title="Wallets On-chain" surtitle="Suivi de soldes par adresse publique" />

      {/* Wallet list */}
      {wallets && wallets.length > 0 && (
        <GlassCard padding={false} className="p-6 flex flex-col gap-4">
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Wallets suivis</h2>
          <div className="flex flex-col gap-2">
            {wallets.map((w: WalletStatus) => (
              <div key={w.id} className="flex items-center justify-between py-2.5 px-3 bg-gray-50 rounded-[8px]">
                <div className="flex items-center gap-3">
                  <span className="px-1.5 py-0.5 bg-gray-200 rounded text-gray-600" style={{ fontSize: 10, fontWeight: 600 }}>
                    {w.chain}
                  </span>
                  <span className="text-gray-800 font-mono" style={{ fontSize: 12 }}>
                    {shortenAddress(w.address)}
                  </span>
                  {w.label && (
                    <span className="text-gray-500" style={{ fontSize: 12 }}>{w.label}</span>
                  )}
                  {w.lastSyncedAt && (
                    <span className="text-gray-400" style={{ fontSize: 11 }}>
                      {new Date(w.lastSyncedAt).toLocaleString('fr-FR')}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    onClick={() => syncMutation.mutate(w.id)}
                    disabled={syncMutation.isPending}
                    className="p-1.5 text-gray-400 hover:text-gray-700 transition-colors"
                    title="Synchroniser"
                  >
                    {syncMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                  </button>
                  <button
                    onClick={() => removeMutation.mutate(w.id)}
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

      {/* Add wallet */}
      <GlassCard padding={false} className="p-6 flex flex-col gap-5">
        <div>
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Ajouter un wallet</h2>
          <p className="text-gray-500 mt-1" style={{ fontSize: 13 }}>
            Seule l'adresse publique est nécessaire — aucune clé privée.
          </p>
        </div>

        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>Blockchain</label>
            <select
              value={chain}
              onChange={e => setChain(e.target.value as ChainType)}
              className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm bg-white focus:outline-none focus:ring-2 focus:ring-gray-900/20"
            >
              {CHAINS.map(c => (
                <option key={c.value} value={c.value}>{c.label}</option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>Adresse</label>
            <input
              type="text"
              placeholder={selectedChain.placeholder}
              value={address}
              onChange={e => setAddress(e.target.value)}
              className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm font-mono focus:outline-none focus:ring-2 focus:ring-gray-900/20"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>Label (optionnel)</label>
            <input
              type="text"
              placeholder="Mon wallet principal"
              value={label}
              onChange={e => setLabel(e.target.value)}
              className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm focus:outline-none focus:ring-2 focus:ring-gray-900/20"
            />
          </div>

          <button
            onClick={() => addMutation.mutate()}
            disabled={!address || addMutation.isPending}
            className="self-start flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
          >
            {addMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
            Ajouter
          </button>

          {addMutation.isSuccess && syncedAccount && (
            <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
              <CheckCircle2 size={14} /> Ajouté — {formatEur(syncedAccount.currentBalanceEur)}
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
