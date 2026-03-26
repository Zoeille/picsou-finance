import { useState } from 'react'
import { motion } from 'motion/react'
import { ShieldCheck, Key, Info, Loader2, CheckCircle2 } from 'lucide-react'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'
import { api } from '../../lib/api'
import { useAuth } from '../../hooks/useAuth'

export function SettingsPage() {
  const { username } = useAuth()
  const [currentPw, setCurrentPw] = useState('')
  const [newPw, setNewPw] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [pwStatus, setPwStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [pwError, setPwError] = useState<string | null>(null)

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault()
    if (newPw !== confirmPw) {
      setPwError('Les mots de passe ne correspondent pas.')
      return
    }
    if (newPw.length < 8) {
      setPwError('Minimum 8 caractères.')
      return
    }
    setPwStatus('loading')
    setPwError(null)
    try {
      await api.post('/auth/change-password', { currentPassword: currentPw, newPassword: newPw })
      setPwStatus('done')
      setCurrentPw('')
      setNewPw('')
      setConfirmPw('')
    } catch {
      setPwStatus('error')
      setPwError('Mot de passe actuel incorrect.')
    }
  }

  return (
    <GlowBackground
      glows={[
        { color: 'bg-gray-200/20', size: 350, blur: 120, position: '-top-10 right-1/3' },
      ]}
    >
      <PageHeader surtitle="Configuration" title="Paramètres" />

      <div className="flex flex-col gap-4 max-w-lg">
        {/* User info */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-9 h-9 rounded-[12px] bg-gray-100 flex items-center justify-center">
                <ShieldCheck size={16} className="text-gray-500" />
              </div>
              <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>Compte</p>
            </div>
            <div className="flex items-center gap-3 p-3 rounded-[12px] bg-black/[0.02]">
              <div
                className="w-9 h-9 rounded-[14px] bg-gray-100 flex items-center justify-center text-gray-500"
                style={{ fontSize: 13, fontWeight: 600 }}
              >
                {(username ?? 'ME').slice(0, 2).toUpperCase()}
              </div>
              <div>
                <p className="text-gray-900" style={{ fontSize: 13, fontWeight: 600 }}>{username}</p>
                <p className="text-gray-400" style={{ fontSize: 11, fontWeight: 500 }}>Utilisateur unique</p>
              </div>
            </div>
          </GlassCard>
        </motion.div>

        {/* Change password */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.08, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-9 h-9 rounded-[12px] bg-gray-100 flex items-center justify-center">
                <Key size={16} className="text-gray-500" />
              </div>
              <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>
                Changer de mot de passe
              </p>
            </div>

            <form onSubmit={handleChangePassword} className="flex flex-col gap-3">
              <PwField
                label="Mot de passe actuel"
                value={currentPw}
                onChange={setCurrentPw}
                autoComplete="current-password"
              />
              <PwField
                label="Nouveau mot de passe"
                value={newPw}
                onChange={setNewPw}
                autoComplete="new-password"
              />
              <PwField
                label="Confirmer le nouveau mot de passe"
                value={confirmPw}
                onChange={setConfirmPw}
                autoComplete="new-password"
              />

              {pwError && (
                <p className="text-red-500" style={{ fontSize: 12, fontWeight: 500 }}>{pwError}</p>
              )}
              {pwStatus === 'done' && (
                <p className="text-green-600 flex items-center gap-1.5" style={{ fontSize: 12, fontWeight: 500 }}>
                  <CheckCircle2 size={13} /> Mot de passe modifié.
                </p>
              )}

              <motion.button
                type="submit"
                disabled={pwStatus === 'loading'}
                whileTap={{ scale: 0.97 }}
                className="h-9 bg-gray-900 text-white rounded-[10px] flex items-center justify-center gap-1.5 mt-1 disabled:opacity-60"
                style={{ fontSize: 12, fontWeight: 600 }}
              >
                {pwStatus === 'loading' ? <Loader2 size={13} className="animate-spin" /> : null}
                Mettre à jour
              </motion.button>
            </form>
          </GlassCard>
        </motion.div>

        {/* GoCardless info */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.16, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard>
            <div className="flex items-center gap-3 mb-3">
              <div className="w-9 h-9 rounded-[12px] bg-blue-50 flex items-center justify-center">
                <Info size={16} className="text-blue-500" />
              </div>
              <p className="text-gray-900" style={{ fontSize: 15, fontWeight: 600 }}>
                Enable Banking — Configuration
              </p>
            </div>
            <p className="text-gray-500 mb-3" style={{ fontSize: 13, fontWeight: 500, lineHeight: 1.5 }}>
              Les identifiants GoCardless sont configurés via variables d'environnement dans le fichier{' '}
              <code className="text-[#c2c4c9] bg-black/[0.04] px-1 py-0.5 rounded-[4px]" style={{ fontFamily: 'monospace', fontSize: 12 }}>
                .env
              </code>{' '}
              pour des raisons de sécurité.
            </p>
            <div className="p-3 rounded-[12px] bg-black/[0.02] space-y-2">
              <EnvRow name="ENABLEBANKING_APPLICATION_ID" />
              <EnvRow name="ENABLEBANKING_KEY_ID" />
              <EnvRow name="ENABLEBANKING_REDIRECT_URI" />
              <EnvRow name="ENABLEBANKING_PRIVATE_KEY" />
            </div>
            <p className="text-gray-400 mt-3" style={{ fontSize: 11, fontWeight: 500 }}>
              Inscrivez-vous gratuitement sur{' '}
              <a
                href="https://enablebanking.com/"
                target="_blank"
                rel="noopener noreferrer"
                className="text-indigo-500 hover:underline"
              >
                enablebanking.com
              </a>
              {' '}· Clé RSA : <code style={{ fontFamily: 'monospace' }}>openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048</code>
            </p>
          </GlassCard>
        </motion.div>

        {/* Version info */}
        <p className="text-gray-300 text-center" style={{ fontSize: 11, fontWeight: 500 }}>
          Picsou v1.0.0 — Open source, self-hosted
        </p>
      </div>
    </GlowBackground>
  )
}

function PwField({
  label, value, onChange, autoComplete
}: {
  label: string; value: string; onChange: (v: string) => void; autoComplete?: string
}) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-gray-500" style={{ fontSize: 11, fontWeight: 600 }}>
        {label.toUpperCase()}
      </label>
      <input
        type="password"
        value={value}
        onChange={e => onChange(e.target.value)}
        autoComplete={autoComplete}
        required
        className="h-8 px-3 rounded-[10px] bg-black/[0.03] text-[13px] border-none outline-none focus:ring-2 focus:ring-gray-900/10"
      />
    </div>
  )
}

function EnvRow({ name }: { name: string }) {
  return (
    <div className="flex items-center justify-between">
      <code className="text-[#c2c4c9]" style={{ fontFamily: 'monospace', fontSize: 12 }}>{name}</code>
      <span className="text-gray-400" style={{ fontSize: 11, fontWeight: 500 }}>•••••••</span>
    </div>
  )
}
