import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { trApi, type Account } from '../../lib/api'
import { GlassCard, PageHeader } from '../../components/shared'
import { RefreshCw, LogOut, Upload, Loader2, CheckCircle2, AlertCircle, Smartphone } from 'lucide-react'

function formatEur(amount: number) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(amount)
}

function AccountList({ accounts, title }: { accounts: Account[]; title: string }) {
  if (!accounts.length) return null
  return (
    <div className="flex flex-col gap-2">
      <p className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>{title}</p>
      {accounts.map(a => (
        <div key={a.id} className="flex items-center justify-between py-1.5 px-3 bg-gray-50 rounded-[8px]">
          <div className="flex items-center gap-2">
            <div className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: a.color }} />
            <span className="text-gray-800" style={{ fontSize: 13 }}>{a.name}</span>
            <span className="text-gray-400 text-xs">{a.type}</span>
          </div>
          <span className="text-gray-800 font-medium tabular-nums" style={{ fontSize: 13 }}>
            {formatEur(a.currentBalanceEur)}
          </span>
        </div>
      ))}
    </div>
  )
}

export function TrSyncPage() {
  const queryClient = useQueryClient()

  // Step 1: credentials input
  const [phoneNumber, setPhoneNumber] = useState('')
  const [pin, setPin] = useState('')

  // Step 2: 2FA
  const [processId, setProcessId] = useState<string | null>(null)
  const [tan, setTan] = useState('')

  // Synced accounts to display after success
  const [syncedAccounts, setSyncedAccounts] = useState<Account[] | null>(null)

  // CSV
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [csvFile, setCsvFile] = useState<File | null>(null)

  // ─── Queries ────────────────────────────────────────────────────────────────

  const { data: status, isLoading: statusLoading } = useQuery({
    queryKey: ['tr-status'],
    queryFn: trApi.status,
    refetchInterval: 60_000,
  })

  // ─── Mutations ──────────────────────────────────────────────────────────────

  const initiateMutation = useMutation({
    mutationFn: () => trApi.initiateAuth(phoneNumber, pin),
    onSuccess: (data) => setProcessId(data.processId),
  })

  const completeMutation = useMutation({
    mutationFn: ({ pid, code }: { pid: string; code: string }) =>
      trApi.completeAuth(pid, code),
    onSuccess: (accounts) => {
      setProcessId(null)
      setTan('')
      setPhoneNumber('')
      setPin('')
      setSyncedAccounts(accounts)
      queryClient.invalidateQueries({ queryKey: ['tr-status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const syncMutation = useMutation({
    mutationFn: trApi.sync,
    onSuccess: (accounts) => {
      setSyncedAccounts(accounts)
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const clearMutation = useMutation({
    mutationFn: trApi.clearSession,
    onSuccess: () => {
      setSyncedAccounts(null)
      queryClient.invalidateQueries({ queryKey: ['tr-status'] })
    },
  })

  const csvMutation = useMutation({
    mutationFn: (file: File) => trApi.importCsv(file),
    onSuccess: (accounts) => {
      setCsvFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      setSyncedAccounts(accounts)
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  // ─── Derived ────────────────────────────────────────────────────────────────

  const sessionActive = status?.isActive ?? false
  const expiresAt = status?.expiresAt
    ? new Date(status.expiresAt).toLocaleDateString('fr-FR', { day: '2-digit', month: 'long', year: 'numeric' })
    : null

  // ─── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="flex flex-col gap-6 max-w-2xl">
      <PageHeader title="Trade Republic" surtitle="Synchronisation des comptes TR" />

      {/* ── Connection card ─────────────────────────────────────────────────── */}
      <GlassCard padding={false} className="p-6 flex flex-col gap-5">
        {/* Header */}
        <div className="flex items-center justify-between">
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Connexion</h2>
          {statusLoading ? (
            <Loader2 size={16} className="text-gray-400 animate-spin" />
          ) : sessionActive ? (
            <span className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 12, fontWeight: 500 }}>
              <CheckCircle2 size={14} /> Session active
            </span>
          ) : (
            <span className="flex items-center gap-1.5 text-gray-400" style={{ fontSize: 12, fontWeight: 500 }}>
              <AlertCircle size={14} /> Non connecté
            </span>
          )}
        </div>

        {sessionActive ? (
          /* ── Active session ── */
          <div className="flex flex-col gap-4">
            {expiresAt && (
              <p className="text-gray-500" style={{ fontSize: 13 }}>
                Session valide jusqu'au <span className="text-gray-700 font-medium">{expiresAt}</span>
              </p>
            )}
            <div className="flex gap-2">
              <button
                onClick={() => syncMutation.mutate()}
                disabled={syncMutation.isPending}
                className="flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
              >
                {syncMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                Synchroniser
              </button>
              <button
                onClick={() => clearMutation.mutate()}
                disabled={clearMutation.isPending}
                className="flex items-center gap-2 px-4 py-2 bg-red-50 text-red-600 rounded-[10px] text-sm font-medium hover:bg-red-100 transition-colors disabled:opacity-50"
              >
                <LogOut size={14} /> Déconnecter
              </button>
            </div>

            {syncMutation.isSuccess && syncedAccounts && (
              <div className="flex flex-col gap-3">
                <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
                  <CheckCircle2 size={14} /> Synchronisation réussie
                </p>
                <AccountList accounts={syncedAccounts} title="Comptes mis à jour" />
              </div>
            )}
            {syncMutation.isError && (
              <p className="text-red-500" style={{ fontSize: 13 }}>
                Erreur : {(syncMutation.error as Error).message}
              </p>
            )}
          </div>

        ) : processId ? (
          /* ── Step 2: 2FA ── */
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2 text-gray-600 py-2 px-3 bg-blue-50 rounded-[10px]">
              <Smartphone size={14} className="text-blue-500 flex-shrink-0" />
              <p style={{ fontSize: 13 }}>Code envoyé par SMS / notification Trade Republic</p>
            </div>
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                maxLength={6}
                placeholder="123456"
                value={tan}
                onChange={e => setTan(e.target.value.replace(/\D/g, ''))}
                autoFocus
                className="w-32 px-3 py-2 border border-gray-200 rounded-[10px] text-center text-lg font-mono tracking-widest focus:outline-none focus:ring-2 focus:ring-gray-900/20"
              />
              <button
                onClick={() => completeMutation.mutate({ pid: processId, code: tan })}
                disabled={tan.length < 4 || completeMutation.isPending}
                className="px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
              >
                {completeMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : 'Valider'}
              </button>
              <button
                onClick={() => { setProcessId(null); setTan('') }}
                className="px-3 py-2 text-gray-400 hover:text-gray-600 text-sm transition-colors"
              >
                Annuler
              </button>
            </div>
            {completeMutation.isError && (
              <p className="text-red-500" style={{ fontSize: 13 }}>Code invalide ou expiré. Réessayez.</p>
            )}
            {completeMutation.isSuccess && syncedAccounts && (
              <div className="flex flex-col gap-3">
                <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
                  <CheckCircle2 size={14} /> Connecté et synchronisé
                </p>
                <AccountList accounts={syncedAccounts} title="Comptes importés" />
              </div>
            )}
          </div>

        ) : (
          /* ── Step 1: credentials ── */
          <div className="flex flex-col gap-4">
            <p className="text-gray-500" style={{ fontSize: 13 }}>
              Entrez vos identifiants Trade Republic. Ils ne sont jamais stockés — seul le token de session l'est (~30 jours).
            </p>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>Téléphone</label>
                <input
                  type="tel"
                  placeholder="0612345678"
                  value={phoneNumber}
                  onChange={e => setPhoneNumber(e.target.value)}
                  className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm focus:outline-none focus:ring-2 focus:ring-gray-900/20"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>PIN</label>
                <input
                  type="password"
                  inputMode="numeric"
                  placeholder="••••"
                  value={pin}
                  onChange={e => setPin(e.target.value)}
                  className="px-3 py-2 border border-gray-200 rounded-[10px] text-sm focus:outline-none focus:ring-2 focus:ring-gray-900/20"
                />
              </div>
            </div>
            <button
              onClick={() => initiateMutation.mutate()}
              disabled={!phoneNumber || !pin || initiateMutation.isPending}
              className="self-start flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {initiateMutation.isPending && <Loader2 size={14} className="animate-spin" />}
              Recevoir le code
            </button>
            {initiateMutation.isError && (
              <p className="text-red-500" style={{ fontSize: 13 }}>
                {(initiateMutation.error as Error).message}
              </p>
            )}
          </div>
        )}
      </GlassCard>

      {/* ── CSV fallback ────────────────────────────────────────────────────── */}
      <GlassCard padding={false} className="p-6 flex flex-col gap-4">
        <div>
          <h2 className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Import CSV</h2>
          <p className="text-gray-500 mt-1" style={{ fontSize: 13 }}>
            Alternative si la connexion WebSocket échoue.
          </p>
        </div>

        <div className="bg-gray-50 rounded-[10px] p-3 font-mono text-xs text-gray-600 leading-relaxed">
          name,type,balance<br />
          PEA Trade Republic,PEA,15000.50<br />
          CTO Trade Republic,COMPTE_TITRES,5000.00<br />
          Cash TR,CHECKING,250.00
        </div>
        <p className="text-gray-400" style={{ fontSize: 12 }}>
          Types valides : PEA · COMPTE_TITRES · CRYPTO · CHECKING · SAVINGS · LEP · OTHER
        </p>

        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 px-4 py-2 border border-gray-200 rounded-[10px] text-sm font-medium text-gray-600 hover:bg-gray-50 cursor-pointer transition-colors">
            <Upload size={14} />
            {csvFile ? csvFile.name : 'Choisir un fichier…'}
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={e => setCsvFile(e.target.files?.[0] ?? null)}
            />
          </label>
          {csvFile && (
            <button
              onClick={() => csvMutation.mutate(csvFile)}
              disabled={csvMutation.isPending}
              className="flex items-center gap-2 px-4 py-2 bg-gray-900 text-white rounded-[10px] text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {csvMutation.isPending && <Loader2 size={14} className="animate-spin" />}
              Importer
            </button>
          )}
        </div>

        {csvMutation.isSuccess && syncedAccounts && (
          <div className="flex flex-col gap-3">
            <p className="flex items-center gap-1.5 text-emerald-600" style={{ fontSize: 13 }}>
              <CheckCircle2 size={14} /> Import réussi
            </p>
            <AccountList accounts={syncedAccounts} title="Comptes importés" />
          </div>
        )}
        {csvMutation.isError && (
          <p className="text-red-500" style={{ fontSize: 13 }}>
            Erreur : {(csvMutation.error as Error).message}
          </p>
        )}
      </GlassCard>
    </div>
  )
}
