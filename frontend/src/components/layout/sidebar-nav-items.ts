import { LayoutDashboard, Wallet, Target, Settings } from 'lucide-react'

export const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', descKey: 'nav.dashboard.desc' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', descKey: 'nav.accounts.desc' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', descKey: 'nav.goals.desc' },
] as const

// The "classic" sidebar style predates the /budget and /family additions and
// used to carry Settings as its own nav item (moved into the profile menu
// post-PR29) — restore that position when the classic style is selected.
export const CLASSIC_SETTINGS_NAV_ITEM = {
  path: '/settings',
  icon: Settings,
  labelKey: 'nav.settings',
  descKey: 'nav.settings.desc',
} as const
