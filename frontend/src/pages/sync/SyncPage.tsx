import { useState, useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { Search, RefreshCw, CheckCircle2, XCircle, Clock, Loader2, ExternalLink, Trash2 } from 'lucide-react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { syncApi, type Institution } from '../../lib/api'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'

type SyncStatus = 'idle' | 'searching' | 'pending_auth' | 'completing' | 'done' | 'error'

export function SyncPage() {
  const [searchParams] = useSearchParams()
  const qc = useQueryClient()

  const [query, setQuery] = useState('')
  const [selectedInstitution, setSelectedInstitution] = useState<Institution | null>(null)
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('idle')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const handledCode = useRef<string | null>(null)

  // Handle Enable Banking OAuth callback (?code=xxx) — guard against double-fire
  useEffect(() => {
    const code = searchParams.get('code')
    if (code && code !== handledCode.current) {
      handledCode.current = code
      setSyncStatus('completing')
      completeMutation.mutate(code)
    }
  }, [searchParams])

  // Requisition status list
  const { data: statusList } = useQuery({
    queryKey: ['sync-status'],
    queryFn: syncApi.status,
    refetchInterval: 30_000,
  })

  // Institution search
  const { data: institutions, isFetching: searchLoading, error: searchError } = useQuery({
    queryKey: ['institutions', query],
    queryFn: () => syncApi.searchInstitutions(query, ''),
    enabled: query.length >= 2,
    staleTime: 60_000,
    retry: false,
  })

  // Initiate mutation
  const initiateMutation = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => syncApi.initiate(id, name),
    onSuccess: data => {
      setSyncStatus('pending_auth')
      window.open(data.authLink, '_blank', 'noopener,noreferrer')
    },
    onError: (err: Error) => {
      setSyncStatus('error')
      setErrorMsg(err.message)
    },
  })

  // Retry mutation
  const retryMutation = useMutation({
    mutationFn: (id: number) => syncApi.retry(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['accounts'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
      qc.invalidateQueries({ queryKey: ['sync-status'] })
    },
  })

  // Delete connection mutation
  const deleteMutation = useMutation({
    mutationFn: (id: number) => syncApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sync-status'] }),
  })

  // Complete mutation
  const completeMutation = useMutation({
    mutationFn: (reqId: string) => syncApi.complete(reqId),
    onSuccess: () => {
      setSyncStatus('done')
      qc.invalidateQueries({ queryKey: ['accounts'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
      qc.invalidateQueries({ queryKey: ['sync-status'] })
    },
    onError: (err: Error) => {
      setSyncStatus('error')
      setErrorMsg(err.message)
    },
  })

  const handleInitiate = (institution: Institution) => {
    setSelectedInstitution(institution)
    setSyncStatus('searching')
    initiateMutation.mutate({ id: institution.id, name: institution.name })
  }

  const statusBadge = (status: string) => {
    switch (status) {
      case 'LINKED': return <span className="text-green-600 flex items-center gap-1 text-[11px] font-[600]"><CheckCircle2 size={12} /> Lié</span>
      case 'CREATED': return <span className="text-amber-500 flex items-center gap-1 text-[11px] font-[600]"><Clock size={12} /> En attente</span>
      case 'EXPIRED': return <span className="text-gray-400 flex items-center gap-1 text-[11px] font-[600]"><XCircle size={12} /> Expiré</span>
      case 'FAILED': return <span className="text-red-500 flex items-center gap-1 text-[11px] font-[600]"><XCircle size={12} /> Échec</span>
      default: return <span className="text-gray-400 text-[11px]">{status}</span>
    }
  }

  return (
    <GlowBackground
      glows={[
        { color: 'bg-cyan-200/15', size: 350, blur: 120, position: '-top-10 right-1/4' },
        { color: 'bg-blue-100/20', size: 280, blur: 90, position: 'bottom-10 left-1/3' },
      ]}
    >
      <PageHeader surtitle="Open Banking" title="Synchronisation" />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Institution search */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard>
            <p className="text-gray-900 mb-4" style={{ fontSize: 15, fontWeight: 600 }}>
              Connecter une banque
            </p>
            <p className="text-gray-400 mb-4" style={{ fontSize: 12, fontWeight: 500 }}>
              Via Enable Banking (PSD2 / OAuth, gratuit).
              Inscrivez-vous sur{' '}
              <a
                href="https://enablebanking.com/"
                target="_blank"
                rel="noopener noreferrer"
                className="text-indigo-500 hover:underline"
              >
                enablebanking.com
              </a>
              {' '}puis configurez vos clés dans le fichier{' '}
              <code style={{ fontFamily: 'monospace', fontSize: 11 }}>.env</code>.
            </p>

            <div className="relative mb-4">
              <Search size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder="BoursoBank, Revolut, Mock…"
                className="h-8 w-full pl-8 pr-3 rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
              />
            </div>

            {searchLoading && (
              <div className="flex items-center gap-2 text-gray-400 py-2">
                <Loader2 size={13} className="animate-spin" />
                <span style={{ fontSize: 12 }}>Recherche…</span>
              </div>
            )}

            {searchError && (
              <div className="p-3 rounded-[12px] bg-red-50 text-red-600" style={{ fontSize: 12 }}>
                {(searchError as Error).message}
              </div>
            )}

            {!searchLoading && !searchError && institutions?.length === 0 && query.length >= 2 && (
              <p className="text-gray-400 py-2" style={{ fontSize: 12 }}>Aucune banque trouvée pour « {query} ».</p>
            )}

            {institutions && institutions.length > 0 && (
              <div className="flex flex-col gap-1 max-h-56 overflow-y-auto">
                {institutions.map(inst => (
                  <motion.button
                    key={inst.id}
                    whileTap={{ scale: 0.98 }}
                    onClick={() => handleInitiate(inst)}
                    disabled={initiateMutation.isPending}
                    className="flex items-center gap-3 p-3 rounded-[14px] hover:bg-black/[0.03] transition-colors text-left disabled:opacity-50"
                  >
                    {inst.logoUrl ? (
                      <img src={inst.logoUrl} alt={inst.name} className="w-7 h-7 rounded-[8px] object-contain" />
                    ) : (
                      <div className="w-7 h-7 rounded-[8px] bg-gray-100 flex items-center justify-center">
                        <RefreshCw size={13} className="text-gray-400" />
                      </div>
                    )}
                    <div>
                      <p className="text-gray-900" style={{ fontSize: 13, fontWeight: 500 }}>
                        {inst.name}
                      </p>
                      {inst.bic && (
                        <p className="text-gray-400" style={{ fontSize: 10, fontWeight: 500, fontFamily: 'monospace' }}>
                          {inst.bic}
                        </p>
                      )}
                    </div>
                    <ExternalLink size={13} className="text-gray-300 ml-auto flex-shrink-0" />
                  </motion.button>
                ))}
              </div>
            )}

            {/* Status feedback */}
            <AnimatePresence>
              {syncStatus !== 'idle' && (
                <motion.div
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                  className={`mt-4 p-3 rounded-[12px] ${
                    syncStatus === 'done' ? 'bg-green-50 text-green-700' :
                    syncStatus === 'error' ? 'bg-red-50 text-red-600' :
                    'bg-blue-50 text-blue-700'
                  }`}
                  style={{ fontSize: 12, fontWeight: 500 }}
                >
                  {syncStatus === 'searching' && (
                    <span className="flex items-center gap-2"><Loader2 size={12} className="animate-spin" /> Connexion à {selectedInstitution?.name}…</span>
                  )}
                  {syncStatus === 'pending_auth' && (
                    <span className="flex items-center gap-2">
                      <Loader2 size={12} className="animate-spin" />
                      Autorisez l'accès dans l'onglet ouvert — la synchronisation se terminera automatiquement.
                    </span>
                  )}
                  {syncStatus === 'completing' && (
                    <span className="flex items-center gap-2"><Loader2 size={12} className="animate-spin" /> Récupération des comptes…</span>
                  )}
                  {syncStatus === 'done' && (
                    <span className="flex items-center gap-2"><CheckCircle2 size={13} /> Comptes synchronisés avec succès !</span>
                  )}
                  {syncStatus === 'error' && (
                    <span>Erreur : {errorMsg ?? 'Une erreur est survenue.'}</span>
                  )}
                </motion.div>
              )}
            </AnimatePresence>
          </GlassCard>
        </motion.div>

        {/* Requisition status */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.08, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard padding={false} rounded="2xl">
            <div className="px-6 pt-5 pb-4">
              <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>
                Connexions actives
              </p>
            </div>
            <div className="border-t border-black/[0.04]">
              {(statusList ?? []).length === 0 ? (
                <p className="text-gray-400 px-6 py-5" style={{ fontSize: 13 }}>
                  Aucune connexion bancaire configurée.
                </p>
              ) : (
                (statusList ?? []).map(req => (
                  <div
                    key={req.id}
                    className="flex items-center justify-between px-6 py-3.5 border-b border-black/[0.03] last:border-0"
                  >
                    <div>
                      <p className="text-gray-900" style={{ fontSize: 13, fontWeight: 500 }}>
                        {req.institutionName ?? req.requisitionId}
                      </p>
                      <p className="text-gray-400" style={{ fontSize: 10, fontWeight: 500, fontFamily: 'monospace' }}>
                        {req.requisitionId}
                      </p>
                    </div>
                    <div className="flex items-center gap-1.5">
                      {statusBadge(req.status)}
                      {(req.status === 'FAILED' || req.status === 'CREATED') && (
                        <button
                          onClick={() => retryMutation.mutate(req.id)}
                          disabled={retryMutation.isPending}
                          title="Réessayer la synchronisation"
                          className="text-gray-300 hover:text-blue-500 transition-colors p-1 disabled:opacity-50"
                        >
                          {retryMutation.isPending && retryMutation.variables === req.id
                            ? <Loader2 size={13} className="animate-spin" />
                            : <RefreshCw size={13} />
                          }
                        </button>
                      )}
                      <button
                        onClick={() => {
                          if (confirm(`Supprimer la connexion "${req.institutionName}" ?`))
                            deleteMutation.mutate(req.id)
                        }}
                        disabled={deleteMutation.isPending}
                        className="text-gray-300 hover:text-red-400 transition-colors p-1 disabled:opacity-50"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </GlassCard>
        </motion.div>
      </div>
    </GlowBackground>
  )
}
