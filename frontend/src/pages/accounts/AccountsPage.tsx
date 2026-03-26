import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { Plus, Wallet, X, Loader2, Pencil } from 'lucide-react'
import { useAccounts, useCreateAccount, useDeleteAccount, useUpdateAccount } from '../../hooks/useAccounts'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'
import { formatEur, accountTypeLabel } from '../../lib/utils'
import type { Account, AccountType } from '../../lib/api'

const ACCOUNT_TYPES: AccountType[] = ['LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS', 'OTHER']
const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#f97316', '#84cc16']

export function AccountsPage() {
  const { data: accounts, isLoading } = useAccounts()
  const createAccount = useCreateAccount()
  const updateAccount = useUpdateAccount()
  const deleteAccount = useDeleteAccount()
  const navigate = useNavigate()
  const [showForm, setShowForm] = useState(false)
  const [editingAccount, setEditingAccount] = useState<Account | null>(null)
  const [form, setForm] = useState({
    name: '', type: 'SAVINGS' as AccountType, provider: '',
    currency: 'EUR', currentBalance: '', isManual: true, color: '#6366f1', ticker: '',
  })

  const emptyForm = { name: '', type: 'SAVINGS' as AccountType, provider: '', currency: 'EUR', currentBalance: '', isManual: true, color: '#6366f1', ticker: '' }

  const openCreate = () => { setEditingAccount(null); setForm(emptyForm); setShowForm(true) }
  const openEdit = (account: Account) => {
    setEditingAccount(account)
    setForm({
      name: account.name,
      type: account.type,
      provider: account.provider ?? '',
      currency: account.currency,
      currentBalance: String(account.currentBalance),
      isManual: account.isManual,
      color: account.color,
      ticker: account.ticker ?? '',
    })
    setShowForm(true)
  }
  const closeForm = () => { setShowForm(false); setEditingAccount(null) }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const data = {
      name: form.name,
      type: form.type,
      provider: form.provider || undefined,
      currency: form.currency,
      currentBalance: form.currentBalance ? parseFloat(form.currentBalance) : undefined,
      isManual: form.isManual,
      color: form.color,
      ticker: form.ticker || undefined,
    }
    if (editingAccount) {
      await updateAccount.mutateAsync({ id: editingAccount.id, data })
    } else {
      await createAccount.mutateAsync(data)
    }
    closeForm()
  }

  return (
    <GlowBackground
      glows={[
        { color: 'bg-emerald-200/15', size: 350, blur: 120, position: '-top-10 right-1/3' },
        { color: 'bg-teal-100/20', size: 280, blur: 100, position: 'bottom-5 left-1/4' },
      ]}
    >
      <PageHeader
        surtitle="Portefeuille"
        title="Comptes"
        actions={
          <motion.button
            whileTap={{ scale: 0.96 }}
            onClick={openCreate}
            className="flex items-center gap-1.5 h-8 px-3 bg-gray-900 text-white rounded-[10px]"
            style={{ fontSize: 12, fontWeight: 600 }}
          >
            <Plus size={14} /> Ajouter
          </motion.button>
        }
      />

      {isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 animate-pulse">
          {[...Array(3)].map((_, i) => (
            <div key={i} className="h-36 bg-white/60 rounded-[20px]" />
          ))}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <AnimatePresence>
            {(accounts ?? []).map((account, i) => (
              <motion.div
                key={account.id}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95 }}
                transition={{ delay: i * 0.06, duration: 0.45, ease: [0.22, 1, 0.36, 1] }}
              >
                <GlassCard
                  onClick={() => navigate(`/accounts/${account.id}`)}
                  className="cursor-pointer hover:shadow-[0_8px_32px_rgba(0,0,0,0.08)] transition-shadow"
                >
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex items-center gap-2.5">
                      <div
                        className="w-9 h-9 rounded-[12px] flex items-center justify-center"
                        style={{ background: account.color + '20' }}
                      >
                        <Wallet size={16} style={{ color: account.color }} />
                      </div>
                      <div>
                        <p className="text-gray-900" style={{ fontSize: 14, fontWeight: 600 }}>
                          {account.name}
                        </p>
                        <p className="text-gray-400" style={{ fontSize: 11, fontWeight: 500 }}>
                          {accountTypeLabel(account.type)}
                          {account.provider ? ` · ${account.provider}` : ''}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-0.5">
                      <button
                        onClick={e => { e.stopPropagation(); openEdit(account) }}
                        className="text-gray-300 hover:text-gray-600 transition-colors p-1"
                      >
                        <Pencil size={13} />
                      </button>
                      <button
                        onClick={e => {
                          e.stopPropagation()
                          if (confirm('Supprimer ce compte ?')) deleteAccount.mutate(account.id)
                        }}
                        className="text-gray-300 hover:text-red-400 transition-colors p-1"
                      >
                        <X size={14} />
                      </button>
                    </div>
                  </div>

                  <p className="text-gray-900 mt-2" style={{ fontSize: 22, fontWeight: 700 }}>
                    {formatEur(account.currentBalanceEur)}
                  </p>
                  {account.currency !== 'EUR' && (
                    <p className="text-gray-400" style={{ fontSize: 11, fontWeight: 500 }}>
                      {account.currentBalance.toFixed(account.currency === 'BTC' ? 8 : 2)} {account.currency}
                    </p>
                  )}
                  {account.lastSyncedAt && (
                    <p className="text-gray-300 mt-1" style={{ fontSize: 11, fontWeight: 500 }}>
                      Sync {new Date(account.lastSyncedAt).toLocaleDateString('fr-FR')}
                    </p>
                  )}
                </GlassCard>
              </motion.div>
            ))}
          </AnimatePresence>

          {(accounts ?? []).length === 0 && (
            <p className="text-gray-400 col-span-3 py-8 text-center" style={{ fontSize: 13 }}>
              Aucun compte. Cliquez sur "Ajouter" pour commencer.
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
                    {editingAccount ? 'Modifier le compte' : 'Nouveau compte'}
                  </h2>
                  <button onClick={closeForm} className="text-gray-400 hover:text-gray-600">
                    <X size={18} />
                  </button>
                </div>

                <form onSubmit={handleSubmit} className="flex flex-col gap-3.5">
                  <Field label="Nom">
                    <input
                      required
                      value={form.name}
                      onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                      placeholder="Mon LEP"
                      className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                    />
                  </Field>

                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Type">
                      <select
                        value={form.type}
                        onChange={e => setForm(f => ({ ...f, type: e.target.value as AccountType }))}
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      >
                        {ACCOUNT_TYPES.map(t => (
                          <option key={t} value={t}>{accountTypeLabel(t)}</option>
                        ))}
                      </select>
                    </Field>
                    <Field label="Devise">
                      <input
                        value={form.currency}
                        onChange={e => setForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))}
                        placeholder="EUR"
                        maxLength={10}
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      />
                    </Field>
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Solde initial">
                      <input
                        type="number"
                        step="any"
                        min="0"
                        value={form.currentBalance}
                        onChange={e => setForm(f => ({ ...f, currentBalance: e.target.value }))}
                        placeholder="0.00"
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      />
                    </Field>
                    <Field label="Ticker (optionnel)">
                      <input
                        value={form.ticker}
                        onChange={e => setForm(f => ({ ...f, ticker: e.target.value.toUpperCase() }))}
                        placeholder="BTC, IWDA.AS"
                        maxLength={20}
                        className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                      />
                    </Field>
                  </div>

                  <Field label="Fournisseur">
                    <input
                      value={form.provider}
                      onChange={e => setForm(f => ({ ...f, provider: e.target.value }))}
                      placeholder="BoursoBank, Revolut…"
                      className="h-8 px-3 w-full rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
                    />
                  </Field>

                  <Field label="Couleur">
                    <div className="flex gap-2 flex-wrap">
                      {COLORS.map(c => (
                        <button
                          key={c} type="button"
                          onClick={() => setForm(f => ({ ...f, color: c }))}
                          className="w-6 h-6 rounded-full transition-transform"
                          style={{
                            background: c,
                            transform: form.color === c ? 'scale(1.25)' : 'scale(1)',
                            boxShadow: form.color === c ? `0 0 0 2px white, 0 0 0 3px ${c}` : undefined,
                          }}
                        />
                      ))}
                    </div>
                  </Field>

                  <div className="flex gap-3 mt-1">
                    <button
                      type="button"
                      onClick={closeForm}
                      className="flex-1 h-9 bg-black/[0.04] text-gray-500 rounded-[10px] text-[12px] font-[500]"
                    >
                      Annuler
                    </button>
                    <button
                      type="submit"
                      disabled={createAccount.isPending || updateAccount.isPending}
                      className="flex-1 h-9 bg-gray-900 text-white rounded-[10px] flex items-center justify-center gap-1.5 text-[12px] font-[600] disabled:opacity-60"
                    >
                      {(createAccount.isPending || updateAccount.isPending) ? <Loader2 size={12} className="animate-spin" /> : null}
                      {editingAccount ? 'Enregistrer' : 'Créer'}
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>
        {label.toUpperCase()}
      </label>
      {children}
    </div>
  )
}
