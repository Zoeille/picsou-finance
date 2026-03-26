import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from 'recharts'
import { ArrowLeft, CalendarDays, Loader2, X } from 'lucide-react'
import { useAccount, useAccountHistory, useAddSnapshot } from '../../hooks/useAccounts'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'
import { formatEur, formatLocalDate, accountTypeLabel } from '../../lib/utils'
import type { BalanceSnapshot } from '../../lib/api'

function getLast12Months() {
  const months = []
  const now = new Date()
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const year = d.getFullYear()
    const month = d.getMonth() + 1
    const key = `${year}-${String(month).padStart(2, '0')}`
    const lastDay = new Date(year, month, 0).getDate()
    const date = `${key}-${String(lastDay).padStart(2, '0')}`
    const label = d.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
    months.push({ key, year, month, date, label })
  }
  return months
}

function snapshotForMonth(history: BalanceSnapshot[] | undefined, year: number, month: number) {
  return history
    ?.filter(s => {
      const [y, m] = s.date.split('-').map(Number)
      return y === year && m === month
    })
    .sort((a, b) => b.date.localeCompare(a.date))[0]
}

export function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const accountId = parseInt(id!, 10)

  const { data: account } = useAccount(accountId)
  const { data: history } = useAccountHistory(accountId)
  const addSnapshot = useAddSnapshot()

  const [showHistory, setShowHistory] = useState(false)

  const months = useMemo(() => getLast12Months(), [])

  // Monthly editor state: key → balance string
  const [values, setValues] = useState<Record<string, string>>({})
  const [modified, setModified] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)

  const openHistory = () => {
    const initial: Record<string, string> = {}
    months.forEach(({ key, year, month }) => {
      const snap = snapshotForMonth(history, year, month)
      if (snap) initial[key] = String(snap.balance)
    })
    setValues(initial)
    setModified(new Set())
    setShowHistory(true)
  }

  const closeHistory = () => { setShowHistory(false); setModified(new Set()) }

  const handleChange = (key: string, value: string) => {
    setValues(prev => ({ ...prev, [key]: value }))
    setModified(prev => new Set([...prev, key]))
  }

  const handleSaveHistory = async () => {
    setSaving(true)
    const toSave = months.filter(({ key }) => modified.has(key) && values[key] !== '')
    await Promise.all(toSave.map(({ key, date, year, month }) => {
      const existing = snapshotForMonth(history, year, month)
      const saveDate = existing ? existing.date : date
      return addSnapshot.mutateAsync({ id: accountId, balance: parseFloat(values[key]), date: saveDate })
    }))
    setSaving(false)
    closeHistory()
  }

  if (!account) return null

  return (
    <GlowBackground
      glows={[
        { color: 'bg-indigo-200/15', size: 350, blur: 120, position: '-top-10 right-1/3' },
      ]}
    >
      <PageHeader
        surtitle={`${accountTypeLabel(account.type)}${account.provider ? ` · ${account.provider}` : ''}`}
        title={account.name}
        actions={
          <div className="flex gap-2">
            <motion.button
              whileTap={{ scale: 0.96 }}
              onClick={() => navigate('/accounts')}
              className="flex items-center gap-1.5 h-8 px-3 bg-black/[0.04] text-gray-500 rounded-[10px]"
              style={{ fontSize: 12, fontWeight: 500 }}
            >
              <ArrowLeft size={13} /> Retour
            </motion.button>
            <motion.button
              whileTap={{ scale: 0.96 }}
              onClick={openHistory}
              className="flex items-center gap-1.5 h-8 px-3 bg-gray-900 text-white rounded-[10px]"
              style={{ fontSize: 12, fontWeight: 600 }}
            >
              <CalendarDays size={14} /> Relevés
            </motion.button>
          </div>
        }
      />

      {/* Balance card */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        className="mb-4"
      >
        <GlassCard>
          <div className="flex items-center gap-3 mb-1">
            <div
              className="w-10 h-10 rounded-[12px] flex items-center justify-center"
              style={{ background: account.color + '20' }}
            >
              <div className="w-4 h-4 rounded-full" style={{ background: account.color }} />
            </div>
            <div>
              <p className="text-gray-400" style={{ fontSize: 12, fontWeight: 500 }}>Solde actuel</p>
              <p className="text-gray-900" style={{ fontSize: 28, fontWeight: 700 }}>
                {formatEur(account.currentBalanceEur)}
              </p>
              {account.currency !== 'EUR' && (
                <p className="text-gray-400" style={{ fontSize: 12, fontWeight: 500 }}>
                  {account.currentBalance} {account.currency}
                  {account.ticker ? ` (${account.ticker})` : ''}
                </p>
              )}
            </div>
          </div>
        </GlassCard>
      </motion.div>

      {/* History chart */}
      {(history ?? []).length > 1 && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.08, ease: [0.22, 1, 0.36, 1] }}
          className="mb-4"
        >
          <GlassCard>
            <p className="text-gray-900 mb-4" style={{ fontSize: 15, fontWeight: 600 }}>
              Historique
            </p>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={history} margin={{ top: 0, right: 0, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="accountGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={account.color} stopOpacity={0.2} />
                    <stop offset="95%" stopColor={account.color} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="rgba(0,0,0,0.04)" strokeDasharray="4 4" vertical={false} />
                <XAxis
                  dataKey="date"
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: '#9ca3af', fontWeight: 500 }}
                  tickFormatter={d => {
                    const [, m, day] = d.split('-')
                    return `${day}/${m}`
                  }}
                  interval="preserveStartEnd"
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: '#9ca3af', fontWeight: 500 }}
                  tickFormatter={v => formatEur(v, { compact: true })}
                  width={60}
                />
                <Tooltip
                  contentStyle={{
                    background: 'rgba(255,255,255,0.9)',
                    backdropFilter: 'blur(8px)',
                    border: '1px solid rgba(255,255,255,0.6)',
                    borderRadius: 12,
                    fontSize: 12,
                  }}
                  formatter={(v: number) => [formatEur(v), 'Solde']}
                  labelFormatter={(l: string) => formatLocalDate(l)}
                />
                <Area
                  type="monotone"
                  dataKey="balance"
                  stroke={account.color}
                  strokeWidth={2}
                  fill="url(#accountGradient)"
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </GlassCard>
        </motion.div>
      )}

      {/* Snapshot list */}
      {(history ?? []).length > 0 && (
        <GlassCard padding={false} rounded="2xl">
          <div className="px-6 pt-5 pb-4">
            <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Snapshots</p>
          </div>
          <div className="border-t border-black/[0.04]">
            {[...(history ?? [])].reverse().slice(0, 10).map(snap => (
              <div
                key={snap.id}
                className="flex items-center justify-between px-6 py-3 border-b border-black/[0.03] last:border-0 hover:bg-black/[0.01] transition-colors"
              >
                <span className="text-gray-500" style={{ fontSize: 13, fontWeight: 500 }}>
                  {formatLocalDate(snap.date)}
                </span>
                <span className="text-gray-900" style={{ fontSize: 13, fontWeight: 600 }}>
                  {formatEur(snap.balance)}
                </span>
              </div>
            ))}
          </div>
        </GlassCard>
      )}

      {/* Monthly history modal */}
      <AnimatePresence>
        {showHistory && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center z-50 p-4"
            onClick={closeHistory}
          >
            <motion.div
              initial={{ scale: 0.95, y: 20 }}
              animate={{ scale: 1, y: 0 }}
              exit={{ scale: 0.95, opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 25 }}
              onClick={e => e.stopPropagation()}
              className="w-full max-w-sm"
            >
              <GlassCard rounded="2xl">
                <div className="flex items-center justify-between mb-1">
                  <h2 className="text-gray-900" style={{ fontSize: 17, fontWeight: 600 }}>
                    Relevés mensuels
                  </h2>
                  <button onClick={closeHistory} className="text-gray-400 hover:text-gray-600">
                    <X size={18} />
                  </button>
                </div>
                <p className="text-gray-400 mb-4" style={{ fontSize: 11, fontWeight: 500 }}>
                  Solde en fin de mois · {account.currency} · modifiez les cases souhaitées
                </p>

                <div className="flex flex-col gap-0 max-h-[60vh] overflow-y-auto -mx-2 px-2">
                  {months.map(({ key, label }) => {
                    const hasData = values[key] !== undefined && values[key] !== ''
                    const isModified = modified.has(key)
                    return (
                      <div key={key} className="flex items-center gap-3 py-2 border-b border-black/[0.04] last:border-0">
                        <span
                          className={`flex-1 capitalize ${isModified ? 'text-gray-900' : hasData ? 'text-gray-600' : 'text-gray-300'}`}
                          style={{ fontSize: 12, fontWeight: isModified ? 600 : 500 }}
                        >
                          {label}
                        </span>
                        <input
                          type="number"
                          step="any"
                          min="0"
                          value={values[key] ?? ''}
                          onChange={e => handleChange(key, e.target.value)}
                          placeholder="—"
                          className="w-28 h-7 px-2 text-right rounded-[8px] bg-black/[0.03] border-none outline-none focus:ring-2 focus:ring-gray-900/10 text-gray-900"
                          style={{ fontSize: 12, fontWeight: 600 }}
                        />
                      </div>
                    )
                  })}
                </div>

                <div className="flex gap-3 mt-4">
                  <button
                    type="button" onClick={closeHistory}
                    className="flex-1 h-9 bg-black/[0.04] text-gray-500 rounded-[10px] text-[12px]"
                  >
                    Annuler
                  </button>
                  <button
                    onClick={handleSaveHistory}
                    disabled={saving || modified.size === 0}
                    className="flex-1 h-9 bg-gray-900 text-white rounded-[10px] flex items-center justify-center gap-1.5 text-[12px] font-[600] disabled:opacity-60"
                  >
                    {saving ? <Loader2 size={12} className="animate-spin" /> : null}
                    {saving ? 'Enregistrement…' : `Enregistrer${modified.size > 0 ? ` (${modified.size})` : ''}`}
                  </button>
                </div>
              </GlassCard>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </GlowBackground>
  )
}
