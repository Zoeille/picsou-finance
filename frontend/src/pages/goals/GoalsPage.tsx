import { useState } from 'react'
import { motion, AnimatePresence } from 'motion/react'
import { Plus, Target, TrendingUp, TrendingDown, X, Loader2, Pencil, Clock } from 'lucide-react'
import { useGoals, useCreateGoal, useDeleteGoal, useUpdateGoal } from '../../hooks/useGoals'
import { useAccounts } from '../../hooks/useAccounts'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'
import { formatEur, formatLocalDate } from '../../lib/utils'
import type { GoalProgress } from '../../lib/api'

export function GoalsPage() {
  const { data: goals, isLoading } = useGoals()
  const { data: accounts } = useAccounts()
  const createGoal = useCreateGoal()
  const updateGoal = useUpdateGoal()
  const deleteGoal = useDeleteGoal()

  const [showForm, setShowForm] = useState(false)
  const [editingGoal, setEditingGoal] = useState<GoalProgress | null>(null)
  const [form, setForm] = useState({
    name: '',
    targetAmount: '',
    deadline: '',
    accountIds: [] as number[],
  })

  const emptyForm = { name: '', targetAmount: '', deadline: '', accountIds: [] as number[] }

  const openCreate = () => { setEditingGoal(null); setForm(emptyForm); setShowForm(true) }
  const openEdit = (goal: GoalProgress) => {
    setEditingGoal(goal)
    setForm({
      name: goal.name,
      targetAmount: String(goal.targetAmount),
      deadline: goal.deadline,
      accountIds: goal.accounts.map(a => a.id),
    })
    setShowForm(true)
  }
  const closeForm = () => { setShowForm(false); setEditingGoal(null) }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const data = {
      name: form.name,
      targetAmount: parseFloat(form.targetAmount),
      deadline: form.deadline,
      accountIds: form.accountIds,
    }
    if (editingGoal) {
      await updateGoal.mutateAsync({ id: editingGoal.id, data })
    } else {
      await createGoal.mutateAsync(data)
    }
    closeForm()
  }

  const toggleAccount = (id: number) => {
    setForm(f => ({
      ...f,
      accountIds: f.accountIds.includes(id)
        ? f.accountIds.filter(a => a !== id)
        : [...f.accountIds, id],
    }))
  }

  return (
    <GlowBackground
      glows={[
        { color: 'bg-violet-200/15', size: 380, blur: 120, position: '-top-10 right-1/4' },
        { color: 'bg-purple-100/20', size: 260, blur: 90, position: 'bottom-5 left-1/3' },
      ]}
    >
      <PageHeader
        surtitle="Planification"
        title="Objectifs"
        actions={
          <motion.button
            whileTap={{ scale: 0.96 }}
            onClick={openCreate}
            className="flex items-center gap-1.5 h-8 px-3 bg-gray-900 text-white rounded-[10px]"
            style={{ fontSize: 12, fontWeight: 600 }}
          >
            <Plus size={14} /> Objectif
          </motion.button>
        }
      />

      {isLoading ? (
        <div className="flex flex-col gap-4 animate-pulse">
          {[...Array(2)].map((_, i) => <div key={i} className="h-48 bg-white/60 rounded-[20px]" />)}
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <AnimatePresence>
            {(goals ?? []).map((goal, i) => {
              const pct = Math.min(100, goal.percentComplete)
              return (
                <motion.div
                  key={goal.id}
                  initial={{ opacity: 0, y: 20 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.97 }}
                  transition={{ delay: i * 0.08, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
                >
                  <GlassCard>
                    <div className="flex items-start justify-between mb-4">
                      <div className="flex items-center gap-3">
                        <div className="w-9 h-9 rounded-[12px] bg-violet-500/10 flex items-center justify-center">
                          <Target size={16} className="text-violet-600" />
                        </div>
                        <div>
                          <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>
                            {goal.name}
                          </p>
                          <p className="text-gray-400" style={{ fontSize: 11, fontWeight: 500 }}>
                            Échéance : {formatLocalDate(goal.deadline)}
                          </p>
                        </div>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <div className="flex items-center gap-2">
                          {(() => {
                            const noData = goal.avgMonthlyContribution == null && goal.monthlyNeeded > 0
                            const reached = goal.monthlyNeeded <= 0
                            return (
                              <div
                                className={`flex items-center gap-1 px-2 py-0.5 rounded-full ${
                                  reached
                                    ? 'bg-green-500/[0.08] text-green-600'
                                    : noData
                                      ? 'bg-gray-500/[0.08] text-gray-400'
                                      : goal.isOnTrack
                                        ? 'bg-green-500/[0.08] text-green-600'
                                        : 'bg-red-500/[0.08] text-red-500'
                                }`}
                                style={{ fontSize: 11, fontWeight: 600 }}
                              >
                                {reached
                                  ? <><TrendingUp size={11} /> Atteint</>
                                  : noData
                                    ? <><Clock size={11} /> En cours</>
                                    : goal.isOnTrack
                                      ? <><TrendingUp size={11} /> En avance</>
                                      : <><TrendingDown size={11} /> En retard</>
                                }
                              </div>
                            )
                          })()}
                          <button
                            onClick={() => openEdit(goal)}
                            className="text-gray-300 hover:text-gray-600 transition-colors p-1"
                          >
                            <Pencil size={13} />
                          </button>
                          <button
                            onClick={() => { if (confirm('Supprimer cet objectif ?')) deleteGoal.mutate(goal.id) }}
                            className="text-gray-300 hover:text-red-400 transition-colors p-1"
                          >
                            <X size={14} />
                          </button>
                        </div>
                        <p className="text-gray-400 text-right" style={{ fontSize: 10, fontWeight: 500 }}>
                          {goal.monthlyNeeded <= 0
                            ? `${formatEur(goal.currentTotal)} ≥ objectif ${formatEur(goal.targetAmount)}`
                            : goal.avgMonthlyContribution == null
                              ? '2 relevés sur mois distincts requis pour calculer'
                              : goal.isOnTrack
                                ? `Épargne moy. ${formatEur(goal.avgMonthlyContribution)}/mois ≥ ${formatEur(goal.monthlyNeeded)}/mois requis`
                                : `Épargne moy. ${formatEur(goal.avgMonthlyContribution)}/mois — manque ${formatEur(goal.monthlyNeeded - goal.avgMonthlyContribution)}/mois`
                          }
                        </p>
                      </div>
                    </div>

                    {/* Progress bar */}
                    <div className="mb-3">
                      <div className="flex justify-between mb-1.5">
                        <span className="text-gray-700" style={{ fontSize: 13, fontWeight: 600 }}>
                          {formatEur(goal.currentTotal)}
                        </span>
                        <span className="text-gray-400" style={{ fontSize: 13, fontWeight: 500 }}>
                          {formatEur(goal.targetAmount)}
                        </span>
                      </div>
                      <div className="w-full bg-gray-100 rounded-full h-2">
                        <motion.div
                          className="h-2 rounded-full"
                          style={{ background: goal.isOnTrack ? '#22c55e' : '#f43f5e' }}
                          initial={{ width: 0 }}
                          animate={{ width: `${pct}%` }}
                          transition={{ duration: 0.8, delay: i * 0.08, ease: [0.22, 1, 0.36, 1] }}
                        />
                      </div>
                      <p className="text-gray-400 mt-1" style={{ fontSize: 11, fontWeight: 500 }}>
                        {pct.toFixed(1)}% atteint
                      </p>
                    </div>

                    {/* Stats grid */}
                    <div className="grid grid-cols-3 gap-3 pt-3 border-t border-black/[0.04]">
                      <Stat
                        label="Mois restants"
                        value={String(goal.monthsLeft)}
                      />
                      <Stat
                        label="À épargner /mois"
                        value={formatEur(goal.monthlyNeeded)}
                        highlight={!goal.isOnTrack}
                      />
                      <Stat
                        label="Contribution moy."
                        value={goal.avgMonthlyContribution != null ? formatEur(goal.avgMonthlyContribution) + '/mois' : '–'}
                        hint={goal.avgMonthlyContribution == null ? 'Min. 2 relevés requis' : undefined}
                        positive={goal.isOnTrack && goal.avgMonthlyContribution != null}
                      />
                    </div>

                    {/* Accounts */}
                    {goal.accounts.length > 0 && (
                      <div className="flex gap-1.5 mt-3 flex-wrap">
                        {goal.accounts.map(a => (
                          <span
                            key={a.id}
                            className="px-2 py-0.5 rounded-full text-white"
                            style={{ fontSize: 10, fontWeight: 600, background: a.color }}
                          >
                            {a.name}
                          </span>
                        ))}
                      </div>
                    )}
                  </GlassCard>
                </motion.div>
              )
            })}
          </AnimatePresence>

          {(goals ?? []).length === 0 && (
            <p className="text-gray-400 py-8 text-center" style={{ fontSize: 13 }}>
              Aucun objectif. Cliquez sur "Objectif" pour en créer un.
            </p>
          )}
        </div>
      )}

      {/* Create / Edit modal */}
      <AnimatePresence>
        {showForm && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center z-50 p-4"
            onClick={closeForm}
          >
            <motion.div
              initial={{ scale: 0.95, y: 20 }}
              animate={{ scale: 1, y: 0 }}
              exit={{ scale: 0.95, opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 25 }}
              onClick={e => e.stopPropagation()}
              className="w-full max-w-md"
            >
              <GlassCard rounded="2xl">
                <div className="flex items-center justify-between mb-5">
                  <h2 className="text-gray-900" style={{ fontSize: 17, fontWeight: 600 }}>
                    {editingGoal ? 'Modifier l\'objectif' : 'Nouvel objectif'}
                  </h2>
                  <button onClick={closeForm} className="text-gray-400 hover:text-gray-600">
                    <X size={18} />
                  </button>
                </div>

                <form onSubmit={handleSubmit} className="flex flex-col gap-3.5">
                  <div className="flex flex-col gap-1">
                    <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>NOM</label>
                    <input
                      required value={form.name}
                      onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                      placeholder="Apport immobilier"
                      className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                    />
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <div className="flex flex-col gap-1">
                      <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>MONTANT CIBLE (€)</label>
                      <input
                        type="number" step="0.01" min="0.01" required
                        value={form.targetAmount}
                        onChange={e => setForm(f => ({ ...f, targetAmount: e.target.value }))}
                        placeholder="50000"
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      />
                    </div>
                    <div className="flex flex-col gap-1">
                      <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>ÉCHÉANCE</label>
                      <input
                        type="date" required
                        value={form.deadline}
                        onChange={e => setForm(f => ({ ...f, deadline: e.target.value }))}
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      />
                    </div>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>COMPTES INCLUS</label>
                    <div className="flex flex-col gap-1.5 max-h-36 overflow-y-auto">
                      {(accounts ?? []).map(a => (
                        <label key={a.id} className="flex items-center gap-2.5 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={form.accountIds.includes(a.id)}
                            onChange={() => toggleAccount(a.id)}
                            className="rounded-[4px] accent-gray-900"
                          />
                          <div
                            className="w-2 h-2 rounded-full flex-shrink-0"
                            style={{ background: a.color }}
                          />
                          <span className="text-gray-700" style={{ fontSize: 12, fontWeight: 500 }}>
                            {a.name}
                          </span>
                          <span className="text-gray-400 ml-auto" style={{ fontSize: 11, fontWeight: 500 }}>
                            {formatEur(a.currentBalanceEur)}
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>

                  <div className="flex gap-3 mt-1">
                    <button
                      type="button" onClick={closeForm}
                      className="flex-1 h-9 bg-black/[0.04] text-gray-500 rounded-[10px] text-[12px]"
                    >
                      Annuler
                    </button>
                    <button
                      type="submit"
                      disabled={createGoal.isPending || updateGoal.isPending || form.accountIds.length === 0}
                      className="flex-1 h-9 bg-gray-900 text-white rounded-[10px] flex items-center justify-center gap-1.5 text-[12px] font-[600] disabled:opacity-60"
                    >
                      {(createGoal.isPending || updateGoal.isPending) ? <Loader2 size={12} className="animate-spin" /> : null}
                      {editingGoal ? 'Enregistrer' : 'Créer'}
                    </button>
                  </div>
                </form>
              </GlassCard>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </GlowBackground>
  )
}

function Stat({ label, value, hint, highlight, positive }: {
  label: string; value: string; hint?: string; highlight?: boolean; positive?: boolean
}) {
  return (
    <div>
      <p className="text-gray-400 mb-0.5" style={{ fontSize: 10, fontWeight: 600 }}>
        {label.toUpperCase()}
      </p>
      <p
        className={highlight ? 'text-red-500' : positive ? 'text-green-600' : 'text-gray-900'}
        style={{ fontSize: 13, fontWeight: 600 }}
      >
        {value}
      </p>
      {hint && (
        <p className="text-gray-300 mt-0.5" style={{ fontSize: 9, fontWeight: 500 }}>
          {hint}
        </p>
      )}
    </div>
  )
}
