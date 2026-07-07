import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  LayoutDashboard,
  Wallet,
  Target,
  Settings,
  LogOut,
  Languages,
  ChevronsUpDown,
  Users,
  Shield,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Item, ItemContent, ItemDescription, ItemMedia, ItemTitle } from '@/components/ui/item'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { useProfileStore } from '@/stores/profile-store'
import { useFamilyMembers } from '@/features/family/hooks'
import { selectSwitchableMembers } from '@/features/family/members'
import { useLogout } from '@/features/auth/hooks'
import { useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import picsouLogo from '@/assets/horizontal-white-picsou.svg'

function NavItem({
  to,
  end,
  icon: Icon,
  title,
  description,
}: {
  to: string
  end?: boolean
  icon: LucideIcon
  title: string
  description: string
}) {
  const location = useLocation()
  const isActive = end
    ? location.pathname === to
    : location.pathname.startsWith(to)

  return (
    <Item
      asChild
      variant={isActive ? 'muted' : 'default'}
      className={cn(
        'rounded-xl px-4 py-3',
        isActive && 'bg-muted ring-1 ring-border',
      )}
    >
      <NavLink to={to} end={end}>
        <ItemMedia
          variant="icon"
          className={cn(
            'flex size-10 items-center justify-center rounded-lg bg-muted text-muted-foreground',
          )}
        >
          <Icon className="size-5" fill={isActive ? 'currentColor' : 'none'} />
        </ItemMedia>
        <ItemContent>
          <ItemTitle className="text-sm font-semibold">{title}</ItemTitle>
          <ItemDescription className="text-xs">{description}</ItemDescription>
        </ItemContent>
      </NavLink>
    </Item>
  )
}

const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', descKey: 'nav.dashboard.desc' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', descKey: 'nav.accounts.desc' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', descKey: 'nav.goals.desc' },
  { path: '/settings', icon: Settings, labelKey: 'nav.settings', descKey: 'nav.settings.desc' },
] as const

export function AppSidebar() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const demoMode = useAppStore((s) => s.demoMode)
  const { activeMemberId, setActiveMember } = useProfileStore()
  const isAdmin = user?.role === 'ADMIN'
  // `/family/members` is admin-only — never call it for non-admins, or the 403
  // interceptor bounces the whole app to /error/403 (notably on a member's first login).
  const { data: familyMembers } = useFamilyMembers({ enabled: isAdmin })
  const logoutMutation = useLogout()
  const queryClient = useQueryClient()

  function switchProfile(memberId: number | null) {
    setActiveMember(memberId)
    queryClient.invalidateQueries()
  }

  // Independent members (own activated password) are private — not switchable by the admin.
  const managedMembers = selectSwitchableMembers(familyMembers ?? [])

  // Resolve active profile display (may differ from logged-in user)
  const activeManaged = activeMemberId
    ? managedMembers.find((m) => m.id === activeMemberId)
    : null
  const displayName = demoMode
    ? 'Demo'
    : activeManaged?.displayName ?? user?.displayName ?? ''
  const displayColor = activeManaged?.avatarColor ?? '#6366f1'
  const initial = displayName.charAt(0).toUpperCase()

  return (
    <nav className="hidden md:flex h-fit max-h-[calc(100vh-2rem)] w-60 shrink-0 flex-col bg-background px-3 py-4 rounded-xl">
      {/* Logo */}
      <img src={picsouLogo} alt="Picsou" className="h-7 w-auto opacity-90 brightness-0 dark:invert" />

      {/* Nav items — evenly distributed */}
      <div className="flex flex-1 flex-col justify-evenly gap-3 mt-[47px]">
        {NAV_ITEMS.map((item) => (
          <NavItem
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            icon={item.icon}
            title={t(item.labelKey)}
            description={t(item.descKey)}
          />
        ))}

        {/* Family view */}
        <NavItem
          to="/family"
          icon={Users}
          title={t('nav.family', 'Family')}
          description={t('nav.family.desc', 'Shared overview')}
        />
      </div>

      {/* User dropdown */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Item asChild variant="default" className="mt-3 rounded-xl px-4 py-3 cursor-pointer hover:bg-muted transition-colors">
            <button type="button">
              <Avatar className="size-10 shrink-0 rounded-lg">
                <AvatarFallback
                  style={activeManaged ? { backgroundColor: displayColor } : undefined}
                  className={cn('text-sm font-bold', activeManaged ? 'text-white' : 'bg-muted text-muted-foreground')}
                >
                  {initial}
                </AvatarFallback>
              </Avatar>
              <ItemContent>
                <ItemTitle className="text-sm font-semibold">{displayName}</ItemTitle>
                <ItemDescription className="text-xs">
                  {demoMode ? 'Demo' : activeManaged ? t('nav.managedProfile', 'Managed profile') : t('nav.account')}
                </ItemDescription>
              </ItemContent>
              <ChevronsUpDown className="ml-auto size-4 text-muted-foreground" />
            </button>
          </Item>
        </DropdownMenuTrigger>
        <DropdownMenuContent side="bottom" align="start" sideOffset={4} className="w-52">
          <DropdownMenuLabel className="font-normal">
            <div className="flex flex-col gap-0.5">
              <p className="text-sm font-medium leading-none">{displayName}</p>
              {demoMode && <p className="text-xs text-muted-foreground">Demo mode</p>}
            </div>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuSub>
            <DropdownMenuSubTrigger>
              <Languages className="mr-2 size-4" />
              {t('settings.language')}
            </DropdownMenuSubTrigger>
            <DropdownMenuSubContent>
              <DropdownMenuRadioGroup
                value={resolveLocale(i18n.language).code}
                onValueChange={(lng) => i18n.changeLanguage(lng)}
              >
                {SUPPORTED_LOCALES.map((locale) => (
                  <DropdownMenuRadioItem key={locale.code} value={locale.code}>
                    {locale.nativeName}
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuRadioGroup>
            </DropdownMenuSubContent>
          </DropdownMenuSub>
          <DropdownMenuSeparator />
          {isAdmin && managedMembers.length > 0 && (
            <>
              <DropdownMenuLabel className="text-xs text-muted-foreground">
                {t('nav.switchProfile', 'Switch profile')}
              </DropdownMenuLabel>
              <DropdownMenuItem
                onClick={() => switchProfile(null)}
                className={cn(activeMemberId === null && 'bg-muted font-medium')}
              >
                <Avatar className="mr-2 size-5 rounded">
                  <AvatarFallback className="bg-muted text-[9px] text-muted-foreground">
                    {(user?.displayName ?? '').charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <span className="truncate">{user?.displayName ?? ''}</span>
              </DropdownMenuItem>
              {managedMembers.map((m) => (
                <DropdownMenuItem
                  key={m.id}
                  onClick={() => switchProfile(m.id)}
                  className={cn(activeMemberId === m.id && 'bg-muted font-medium')}
                >
                  <Avatar className="mr-2 size-5 rounded">
                    <AvatarFallback style={{ backgroundColor: m.avatarColor }} className="text-[9px] text-white">
                      {m.displayName.charAt(0)}
                    </AvatarFallback>
                  </Avatar>
                  <span className="truncate">{m.displayName}</span>
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
            </>
          )}
          {isAdmin && (
            <>
              <DropdownMenuItem onClick={() => navigate('/admin')}>
                <Shield className="mr-2 size-4" />
                {t('nav.admin')}
              </DropdownMenuItem>
              <DropdownMenuSeparator />
            </>
          )}
          <DropdownMenuItem onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
            <LogOut className="mr-2 size-4" />
            {t('settings.logout')}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </nav>
  )
}
