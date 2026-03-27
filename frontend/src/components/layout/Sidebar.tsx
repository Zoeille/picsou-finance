import { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import {
  LayoutDashboard,
  Wallet,
  Target,
  RefreshCw,
  Settings,
  LogOut,
  DollarSign,
  TrendingUp,
  Bitcoin,
  Link,
} from 'lucide-react'
import { useAuth } from '../../hooks/useAuth'

const NAV_ITEMS = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/accounts', label: 'Comptes', icon: Wallet },
  { to: '/goals', label: 'Objectifs', icon: Target },
  { to: '/sync', label: 'Synchronisation', icon: RefreshCw },
  { to: '/sync/trade-republic', label: 'Trade Republic', icon: TrendingUp },
  { to: '/sync/crypto-exchange', label: 'Exchanges', icon: Bitcoin },
  { to: '/sync/crypto-wallet', label: 'Wallets', icon: Link },
] as const

export function Sidebar() {
  const [expanded, setExpanded] = useState(false)
  const { username, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  const initials = username
    ? username.slice(0, 2).toUpperCase()
    : 'ME'

  return (
    <motion.aside
      className="flex flex-col h-screen bg-white/70 backdrop-blur-2xl border-r border-black/[0.04] flex-shrink-0 overflow-hidden"
      animate={{ width: expanded ? 220 : 68 }}
      transition={{ type: 'spring', stiffness: 300, damping: 28 }}
      onMouseEnter={() => setExpanded(true)}
      onMouseLeave={() => setExpanded(false)}
    >
      {/* Avatar */}
      <div className="flex items-center gap-3 px-[14px] py-5">
        <div
          className="flex-shrink-0 flex items-center justify-center bg-gray-100 rounded-[14px]"
          style={{ width: 40, height: 40, fontSize: 13, fontWeight: 600, color: '#6b7280' }}
        >
          {initials}
        </div>
        <AnimatePresence>
          {expanded && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ delay: 0.08, duration: 0.15 }}
              className="overflow-hidden"
            >
              <p className="text-gray-900 whitespace-nowrap" style={{ fontSize: 13, fontWeight: 600 }}>
                {username ?? 'Utilisateur'}
              </p>
              <p className="text-gray-400 whitespace-nowrap" style={{ fontSize: 11, fontWeight: 500 }}>
                Patrimoine perso
              </p>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Logo zone */}
      <div className="flex items-center gap-3 px-[14px] pb-3">
        <div
          className="flex-shrink-0 flex items-center justify-center bg-gray-900 rounded-[12px]"
          style={{ width: 40, height: 40 }}
        >
          <DollarSign size={18} className="text-white" />
        </div>
        <AnimatePresence>
          {expanded && (
            <motion.span
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ delay: 0.08, duration: 0.15 }}
              className="text-gray-900 whitespace-nowrap"
              style={{ fontSize: 15, fontWeight: 700 }}
            >
              Picsou
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      {/* Separator */}
      <div className="h-px bg-black/[0.05] mx-4 mb-2" />

      {/* Navigation */}
      <nav className="flex flex-col gap-1 px-2 flex-1">
        {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} end={to === '/'}>
            {({ isActive }) => (
              <div className="relative flex items-center gap-3 h-11 px-3 rounded-[14px] cursor-pointer">
                {isActive && (
                  <motion.div
                    layoutId="activeNav"
                    className="absolute inset-0 bg-black/[0.06] rounded-[14px]"
                    transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                  />
                )}
                <div
                  className={`relative flex-shrink-0 transition-colors ${isActive ? 'text-gray-900' : 'text-gray-400'}`}
                >
                  <Icon size={18} />
                </div>
                <AnimatePresence>
                  {expanded && (
                    <motion.span
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      exit={{ opacity: 0 }}
                      transition={{ delay: 0.08, duration: 0.15 }}
                      className={`relative whitespace-nowrap transition-colors ${
                        isActive ? 'text-gray-900' : 'text-gray-400'
                      }`}
                      style={{ fontSize: 13, fontWeight: 500 }}
                    >
                      {label}
                    </motion.span>
                  )}
                </AnimatePresence>
              </div>
            )}
          </NavLink>
        ))}
      </nav>

      {/* Separator */}
      <div className="h-px bg-black/[0.05] mx-4 mb-2" />

      {/* Settings + Logout */}
      <div className="flex flex-col gap-1 px-2 pb-5">
        <NavLink to="/settings">
          {({ isActive }) => (
            <div className="relative flex items-center gap-3 h-11 px-3 rounded-[14px] cursor-pointer">
              {isActive && (
                <motion.div
                  layoutId="activeNav"
                  className="absolute inset-0 bg-black/[0.06] rounded-[14px]"
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                />
              )}
              <Settings
                size={18}
                className={`relative flex-shrink-0 ${isActive ? 'text-gray-900' : 'text-gray-400'}`}
              />
              <AnimatePresence>
                {expanded && (
                  <motion.span
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                    transition={{ delay: 0.08, duration: 0.15 }}
                    className={`relative whitespace-nowrap ${isActive ? 'text-gray-900' : 'text-gray-400'}`}
                    style={{ fontSize: 13, fontWeight: 500 }}
                  >
                    Paramètres
                  </motion.span>
                )}
              </AnimatePresence>
            </div>
          )}
        </NavLink>

        <button
          onClick={handleLogout}
          className="flex items-center gap-3 h-11 px-3 rounded-[14px] text-gray-400 hover:text-red-500 hover:bg-red-50/50 transition-colors w-full"
        >
          <LogOut size={18} className="flex-shrink-0" />
          <AnimatePresence>
            {expanded && (
              <motion.span
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ delay: 0.08, duration: 0.15 }}
                className="whitespace-nowrap"
                style={{ fontSize: 13, fontWeight: 500 }}
              >
                Déconnexion
              </motion.span>
            )}
          </AnimatePresence>
        </button>
      </div>
    </motion.aside>
  )
}
