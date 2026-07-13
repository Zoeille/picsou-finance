import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  ChevronDown,
  LayoutDashboard,
  Settings,
  Wallet,
  Target,
  PieChart,
  Users,
  LogOut,
  Shield,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Item, ItemContent, ItemDescription, ItemMedia, ItemTitle } from '@/components/ui/item'
import { useFamilyMembers } from '@/features/family/hooks'
import { selectSwitchableMembers } from '@/features/family/members'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { useLogout } from '@/features/auth/hooks'
import { LanguageToggle } from './LanguageToggle'
import { useProfileStore } from '@/stores/profile-store'
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
        'min-h-[72px] rounded-xl px-4 py-3',
        isActive && 'bg-muted ring-1 ring-border',
      )}
    >
      <NavLink to={to} end={end}>
        <ItemMedia
          variant="icon"
          className={cn(
            'flex size-10 items-center justify-center rounded-lg bg-muted text-muted-foreground',
            isActive && 'text-foreground',
          )}
        >
          <Icon className="size-5" aria-hidden="true" />
        </ItemMedia>
        <ItemContent>
          <ItemTitle className="text-sm font-semibold">{title}</ItemTitle>
          <ItemDescription className="line-clamp-1 text-[11px] leading-4">{description}</ItemDescription>
        </ItemContent>
      </NavLink>
    </Item>
  )
}

const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', descKey: 'nav.dashboard.desc' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', descKey: 'nav.accounts.desc' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', descKey: 'nav.goals.desc' },
  { path: '/budget', icon: PieChart, labelKey: 'nav.budget', descKey: 'nav.budget.desc' },
] as const

// The "classic" sidebar style predates the /budget and /family additions and
// used to carry Settings as its own nav item (moved into the profile menu
// post-PR29) — restore that position when the classic style is selected.
const CLASSIC_SETTINGS_NAV_ITEM = {
  path: '/settings',
  icon: Settings,
  labelKey: 'nav.settings',
  descKey: 'nav.settings.desc',
} as const

export function AppSidebar() {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const demoMode = useAppStore((s) => s.demoMode)
  const sidebarStyle = useAppStore((s) => s.sidebarStyle)
  const activeMemberId = useProfileStore((s) => s.activeMemberId)
  const setActiveMember = useProfileStore((s) => s.setActiveMember)
  const canSwitchProfile = !demoMode && user?.role === 'ADMIN'
  const isAdmin = user?.role === 'ADMIN'
  const { data: familyMembers = [] } = useFamilyMembers({ enabled: canSwitchProfile })
  const switchableMembers = selectSwitchableMembers(familyMembers)
  const activeMember = switchableMembers.find((member) => member.id === activeMemberId)
  const displayName = demoMode
    ? 'Demo'
    : user?.displayName ?? ''
  const activeDisplayName = activeMember?.displayName ?? (activeMemberId ? t('nav.unknownProfile') : displayName)
  const activeDescription = activeMemberId ? t('nav.managedProfile') : demoMode ? 'Demo' : t('nav.account')
  const initial = displayName.charAt(0).toUpperCase()
  const activeInitial = activeDisplayName.charAt(0).toUpperCase()
  const activeProfileValue = activeMemberId == null ? 'own' : `member-${activeMemberId}`
  const settingsActive = location.pathname.startsWith('/settings')
  const logoutMutation = useLogout()
  const isClassic = sidebarStyle === 'classic'
  const navItems = isClassic ? [...NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM] : NAV_ITEMS

  function handleProfileValueChange(value: string) {
    const nextMemberId = value === 'own'
      ? null
      : Number(value.replace('member-', ''))

    if (nextMemberId !== null && !Number.isFinite(nextMemberId)) return
    if (nextMemberId === activeMemberId) return

    setActiveMember(nextMemberId)
    void queryClient.invalidateQueries()
  }

  return (
    <nav
      className={cn(
        'hidden shrink-0 flex-col rounded-xl bg-background px-3 py-4 md:flex',
        isClassic ? 'h-fit max-h-[calc(100vh-2rem)] w-60' : 'h-[calc(100vh-2rem)] w-72',
      )}
    >
      {/* Logo */}
      <img
        src={picsouLogo}
        alt="Picsou"
        className={cn(
          'h-7 w-auto opacity-90 brightness-0 dark:invert',
          isClassic ? undefined : 'mb-4 self-start px-4',
        )}
      />

      <div className={cn('flex flex-col gap-3', isClassic && 'mt-[47px] flex-1 justify-evenly')}>
        {navItems.map((item) => (
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
          title={t('nav.family')}
          description={t('nav.family.desc')}
        />
      </div>

      {/* Language toggle — always visible, next to theme (in /settings > Appearance).
          Not shown in classic style: language lives in the profile dropdown there, as it did pre-PR29. */}
      {!isClassic && (
        <div className="mt-3 flex justify-center">
          <LanguageToggle />
        </div>
      )}

      {isClassic ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Item
              asChild
              variant={settingsActive ? 'muted' : 'default'}
              className={cn(
                'mt-3 min-h-[72px] rounded-xl px-4 py-3 text-left transition-colors hover:bg-muted',
                settingsActive && 'bg-muted ring-1 ring-border',
              )}
            >
              <button type="button" aria-label={t('nav.switchProfile')}>
                <Avatar className="size-10 shrink-0 rounded-lg">
                  <AvatarFallback
                    className={cn(
                      'text-sm font-bold',
                      activeMember ? 'text-primary-foreground' : 'bg-muted text-muted-foreground',
                    )}
                    style={activeMember ? { backgroundColor: activeMember.avatarColor } : undefined}
                  >
                    {activeInitial}
                  </AvatarFallback>
                </Avatar>
                <ItemContent className="min-w-0">
                  <ItemTitle className="max-w-40 truncate text-sm font-semibold">{activeDisplayName}</ItemTitle>
                  <ItemDescription className="text-xs">{activeDescription}</ItemDescription>
                </ItemContent>
                <ChevronDown className="ml-auto size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            </Item>
          </DropdownMenuTrigger>

          <DropdownMenuContent side="top" align="start" className="w-64">
            <DropdownMenuLabel className="font-normal">
              <div className="flex flex-col gap-0.5">
                <p className="text-sm font-medium leading-none">{displayName}</p>
                {demoMode && <p className="text-xs text-muted-foreground">Demo mode</p>}
              </div>
            </DropdownMenuLabel>
            {canSwitchProfile && switchableMembers.length > 0 && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuLabel className="text-xs text-muted-foreground">
                  {t('nav.switchProfile')}
                </DropdownMenuLabel>
                <DropdownMenuItem
                  onClick={() => handleProfileValueChange('own')}
                  className={cn(activeMemberId === null && 'bg-muted font-medium')}
                >
                  <Avatar className="size-6 shrink-0 rounded-md">
                    <AvatarFallback className="bg-muted text-[10px] font-bold text-muted-foreground">
                      {initial}
                    </AvatarFallback>
                  </Avatar>
                  <span className="truncate">{displayName}</span>
                </DropdownMenuItem>
                {switchableMembers.map((member) => (
                  <DropdownMenuItem
                    key={member.id}
                    onClick={() => handleProfileValueChange(`member-${member.id}`)}
                    className={cn(activeMemberId === member.id && 'bg-muted font-medium')}
                  >
                    <span
                      className="size-6 shrink-0 rounded-md"
                      style={{ backgroundColor: member.avatarColor }}
                      aria-hidden="true"
                    />
                    <span className="truncate">{member.displayName}</span>
                  </DropdownMenuItem>
                ))}
              </>
            )}

            <DropdownMenuSeparator />

            {isAdmin && (
              <DropdownMenuItem onClick={() => navigate('/admin')}>
                <Shield className="size-4" aria-hidden="true" />
                <span>{t('nav.admin')}</span>
              </DropdownMenuItem>
            )}

            <DropdownMenuItem onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
              <LogOut className="size-4" aria-hidden="true" />
              <span>{t('settings.logout')}</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <div className="mt-auto flex items-center gap-2">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Item
                asChild
                variant="default"
                className="min-h-[72px] flex-1 rounded-xl px-4 py-3 text-left transition-colors hover:bg-muted"
              >
                <button type="button" aria-label={t('nav.account')}>
                  <Avatar className="size-10 shrink-0 rounded-lg">
                    <AvatarFallback
                      className={cn(
                        'text-sm font-bold',
                        activeMember ? 'text-primary-foreground' : 'bg-muted text-muted-foreground',
                      )}
                      style={activeMember ? { backgroundColor: activeMember.avatarColor } : undefined}
                    >
                      {activeInitial}
                    </AvatarFallback>
                  </Avatar>
                  <ItemContent className="min-w-0">
                    <ItemTitle className="max-w-28 truncate text-sm font-semibold">{activeDisplayName}</ItemTitle>
                    <ItemDescription className="truncate text-xs">{activeDescription}</ItemDescription>
                  </ItemContent>
                  <ChevronDown className="ml-auto size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                </button>
              </Item>
            </DropdownMenuTrigger>

            <DropdownMenuContent side="top" align="start" className="w-64">
              {canSwitchProfile && switchableMembers.length > 0 && (
                <>
                  <DropdownMenuRadioGroup value={activeProfileValue} onValueChange={handleProfileValueChange}>
                    <DropdownMenuRadioItem value="own">
                      <Avatar className="size-8 shrink-0 rounded-lg">
                        <AvatarFallback className="bg-muted text-xs font-bold text-muted-foreground">
                          {initial}
                        </AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <p className="truncate font-medium">{displayName}</p>
                        <p className="truncate text-xs text-muted-foreground">{t('nav.account')}</p>
                      </div>
                    </DropdownMenuRadioItem>

                    {switchableMembers.map((member) => (
                      <DropdownMenuRadioItem key={member.id} value={`member-${member.id}`}>
                        <span
                          className="size-8 shrink-0 rounded-lg"
                          style={{ backgroundColor: member.avatarColor }}
                          aria-hidden="true"
                        />
                        <div className="min-w-0">
                          <p className="truncate font-medium">{member.displayName}</p>
                          <p className="truncate text-xs text-muted-foreground">{t('nav.managedProfile')}</p>
                        </div>
                      </DropdownMenuRadioItem>
                    ))}
                  </DropdownMenuRadioGroup>

                  <DropdownMenuSeparator />
                </>
              )}

              {isAdmin && (
                <DropdownMenuItem onClick={() => navigate('/admin')}>
                  <Shield className="size-4" aria-hidden="true" />
                  <span>{t('nav.admin')}</span>
                </DropdownMenuItem>
              )}

              <DropdownMenuItem onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
                <LogOut className="size-4" aria-hidden="true" />
                <span>{t('settings.logout')}</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <Button variant="ghost" size="icon" asChild className={cn(settingsActive && 'bg-muted text-foreground')}>
            <NavLink to="/settings" aria-label={t('nav.settings')}>
              <Settings className="size-4" aria-hidden="true" />
            </NavLink>
          </Button>
        </div>
      )}
    </nav>
  )
}
