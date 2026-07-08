import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  LayoutDashboard,
  Wallet,
  Target,
  Settings,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import picsouLogo from '@/assets/picsou_logo_white.svg'

const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', end: true },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', end: false },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', end: false },
  { path: '/settings', icon: Settings, labelKey: 'nav.settings', end: false },
] as const

function MobileNavItem({
  to,
  end,
  icon: Icon,
  label,
}: {
  to: string
  end: boolean
  icon: LucideIcon
  label: string
}) {
  const location = useLocation()
  const isActive = end
    ? location.pathname === to
    : location.pathname.startsWith(to)

  return (
    <NavLink
      to={to}
      end={end}
      title={label}
      className={cn(
        'flex size-10 items-center justify-center rounded-lg bg-muted text-muted-foreground transition-colors',
        isActive && 'ring-1 ring-border bg-muted text-foreground',
      )}
    >
      <Icon className="size-5" aria-hidden="true" />
    </NavLink>
  )
}

export function MobileBottomNav() {
  const { t } = useTranslation()

  return (
    <nav className="fixed bottom-4 inset-x-4 z-50 md:hidden">
      <div className="flex items-center justify-between rounded-xl bg-background px-3 py-3 ring-1 ring-border">
        {/* Left items */}
        <div className="flex gap-2">
          {NAV_ITEMS.slice(0, 2).map((item) => (
            <MobileNavItem
              key={item.path}
              to={item.path}
              end={item.end}
              icon={item.icon}
              label={t(item.labelKey)}
            />
          ))}
        </div>

        {/* Center logo */}
        <div className="flex-1 flex justify-center">
          <img
            src={picsouLogo}
            alt="Picsou"
            className="h-8 w-auto opacity-90 brightness-0 dark:invert"
          />
        </div>

        {/* Right items */}
        <div className="flex gap-2">
          {NAV_ITEMS.slice(2).map((item) => (
            <MobileNavItem
              key={item.path}
              to={item.path}
              end={item.end}
              icon={item.icon}
              label={t(item.labelKey)}
            />
          ))}
        </div>
      </div>
      {/* iOS safe area */}
      <div className="h-[env(safe-area-inset-bottom)]" />
    </nav>
  )
}
