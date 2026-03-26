import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { DollarSign, Eye, EyeOff, Loader2 } from 'lucide-react'
import { useAuth } from '../../hooks/useAuth'
import { GlassCard } from '../../components/shared'
import { safeRedirect } from '../../lib/utils'

export function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const { login } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(username, password)
      navigate(redirect, { replace: true })
    } catch {
      setError('Identifiants incorrects.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-[#f5f5f7] flex items-center justify-center relative overflow-hidden">
      {/* Glows */}
      <div
        className="absolute -top-20 right-1/3 rounded-full bg-indigo-200/20 pointer-events-none"
        style={{ width: 400, height: 400, filter: 'blur(120px)' }}
      />
      <div
        className="absolute bottom-0 left-1/4 rounded-full bg-violet-200/15 pointer-events-none"
        style={{ width: 300, height: 300, filter: 'blur(100px)' }}
      />

      <motion.div
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
        className="w-full max-w-sm px-4"
      >
        {/* Logo */}
        <div className="flex flex-col items-center mb-8">
          <div
            className="flex items-center justify-center bg-gray-900 rounded-[20px] mb-4"
            style={{ width: 56, height: 56 }}
          >
            <DollarSign size={26} className="text-white" />
          </div>
          <h1 className="text-gray-900" style={{ fontSize: 28, fontWeight: 700 }}>Picsou</h1>
          <p className="text-gray-400 mt-1" style={{ fontSize: 13, fontWeight: 500 }}>
            Votre patrimoine, en un coup d'œil.
          </p>
        </div>

        <GlassCard>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>
                Identifiant
              </label>
              <input
                type="text"
                value={username}
                onChange={e => setUsername(e.target.value)}
                autoComplete="username"
                required
                className="h-9 px-3 rounded-[10px] bg-black/[0.03] text-[13px] text-gray-900 border-none outline-none focus:ring-2 focus:ring-gray-900/10 transition-shadow"
                placeholder="admin"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-gray-500" style={{ fontSize: 12, fontWeight: 500 }}>
                Mot de passe
              </label>
              <div className="relative">
                <input
                  type={showPw ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                  className="h-9 w-full px-3 pr-9 rounded-[10px] bg-black/[0.03] text-[13px] text-gray-900 border-none outline-none focus:ring-2 focus:ring-gray-900/10 transition-shadow"
                  placeholder="••••••••"
                />
                <button
                  type="button"
                  onClick={() => setShowPw(v => !v)}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 transition-colors"
                >
                  {showPw ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </div>

            {error && (
              <p className="text-red-500" style={{ fontSize: 12, fontWeight: 500 }}>
                {error}
              </p>
            )}

            <motion.button
              type="submit"
              disabled={loading}
              whileTap={{ scale: 0.97 }}
              className="h-9 bg-gray-900 text-white rounded-[10px] flex items-center justify-center gap-2 mt-1 disabled:opacity-60 transition-opacity"
              style={{ fontSize: 13, fontWeight: 600 }}
            >
              {loading ? <Loader2 size={14} className="animate-spin" /> : null}
              {loading ? 'Connexion…' : 'Se connecter'}
            </motion.button>
          </form>
        </GlassCard>
      </motion.div>
    </div>
  )
}
