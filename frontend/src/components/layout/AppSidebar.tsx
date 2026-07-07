import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  ChevronDown,
  LayoutDashboard,
  Settings,
  Wallet,
  Target,
  Users,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
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
import { useProfileStore } from '@/stores/profile-store'
import { cn } from '@/lib/utils'

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
] as const

export function AppSidebar() {
  const { t } = useTranslation()
  const location = useLocation()
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const demoMode = useAppStore((s) => s.demoMode)
  const activeMemberId = useProfileStore((s) => s.activeMemberId)
  const setActiveMember = useProfileStore((s) => s.setActiveMember)
  const canSwitchProfile = !demoMode && user?.role === 'ADMIN'
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
    <nav className="hidden h-[calc(100vh-2rem)] max-h-[calc(100vh-2rem)] w-72 shrink-0 flex-col rounded-xl bg-background px-3 py-4 md:flex">
      <div className="flex flex-col gap-3">
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
          title={t('nav.family')}
          description={t('nav.family.desc')}
        />
      </div>

      {canSwitchProfile ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Item
              asChild
              variant={settingsActive ? 'muted' : 'default'}
              className={cn(
                'mt-auto min-h-[72px] rounded-xl px-4 py-3 text-left transition-colors hover:bg-muted',
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
                  <ItemDescription className="text-xs">
                    {activeDescription}
                  </ItemDescription>
                </ItemContent>
                <ChevronDown className="ml-auto size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            </Item>
          </DropdownMenuTrigger>

          <DropdownMenuContent side="top" align="start" className="w-64">
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

            <DropdownMenuItem asChild>
              <NavLink to="/settings">
                <Settings className="size-4" aria-hidden="true" />
                <span>{t('nav.settings')}</span>
              </NavLink>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <Item
          asChild
          variant={settingsActive ? 'muted' : 'default'}
          className={cn(
            'mt-auto min-h-[72px] rounded-xl px-4 py-3 transition-colors hover:bg-muted',
            settingsActive && 'bg-muted ring-1 ring-border',
          )}
        >
          <NavLink to="/settings">
            <Avatar className="size-10 shrink-0 rounded-lg">
              <AvatarFallback className="bg-muted text-sm font-bold text-muted-foreground">
                {initial}
              </AvatarFallback>
            </Avatar>
            <ItemContent>
              <ItemTitle className="text-sm font-semibold">{displayName}</ItemTitle>
              <ItemDescription className="text-xs">
                {demoMode ? 'Demo' : t('nav.account')}
              </ItemDescription>
            </ItemContent>
          </NavLink>
        </Item>
      )}
    </nav>
  )
}
