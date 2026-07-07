import React, { useEffect, useState } from 'react'
import { type Theme, applyTheme, getStoredTheme } from '@/lib/theme'
import { useTranslation } from 'react-i18next'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'
import { useNavigate } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore, type DateFormat } from '@/stores/app-store'
import { useLogout } from '@/features/auth/hooks'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getErrorStatus } from '@/lib/errors'
import {
  Paintbrush,
  Globe,
  User,
  LogOut,
  Users,
  ChevronRight,
  Check,
  X,
  Pencil,
  Shield,
  KeyRound,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { api } from '@/lib/api-client'
import { SecuritySection } from './security/SecuritySection'
import { AccessKeysSection } from './sections/AccessKeysSection'

// ---------------------------------------------------------------------------
// Toggle group button (theme / language)
// ---------------------------------------------------------------------------

interface ToggleOption {
  value: string
  label: string
}

function ToggleGroup({
  options,
  value,
  onChange,
}: {
  options: ToggleOption[]
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="inline-flex items-center rounded-lg bg-muted p-1">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
            value === opt.value
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Settings section card wrapper
// ---------------------------------------------------------------------------

function SectionCard({
  icon,
  title,
  description,
  children,
}: {
  icon: LucideIcon
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          {React.createElement(icon, { className: "size-5 text-muted-foreground" })}
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// SettingsPage
// ---------------------------------------------------------------------------

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const logoutMutation = useLogout()
  const setUsername = useAuthStore((s) => s.setUsername)
  const { dateFormat, setDateFormat } = useAppStore()

  // Username editing -------------------------------------------------------
  const [editingUsername, setEditingUsername] = useState(false)
  const [newUsername, setNewUsername] = useState('')
  const [usernameError, setUsernameError] = useState<string | null>(null)
  const [usernameSaving, setUsernameSaving] = useState(false)

  function startEditUsername() {
    setNewUsername(user?.username ?? '')
    setUsernameError(null)
    setEditingUsername(true)
  }

  function cancelEditUsername() {
    setEditingUsername(false)
    setUsernameError(null)
  }

  async function saveUsername() {
    const trimmed = newUsername.trim()
    if (!trimmed || trimmed === user?.username) { setEditingUsername(false); return }
    if (trimmed.length < 3) { setUsernameError('3 caractères minimum'); return }
    if (!/^[a-zA-Z0-9._-]+$/.test(trimmed)) { setUsernameError('Lettres, chiffres, . _ - uniquement'); return }
    setUsernameSaving(true)
    setUsernameError(null)
    try {
      await api.patch('/auth/username', { newUsername: trimmed })
      setUsername(trimmed)
      setEditingUsername(false)
    } catch (err: unknown) {
      setUsernameError(getErrorStatus(err) === 409 ? 'Nom d\'utilisateur déjà pris' : 'Erreur, réessayez')
    } finally {
      setUsernameSaving(false)
    }
  }

  // Theme -----------------------------------------------------------------
  const [theme, setTheme] = useState<Theme>(getStoredTheme)

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  // Language --------------------------------------------------------------
  const [locale, setLocale] = useState(i18n.language)

  // i18next persists the choice itself (localStorage key "picsou-locale").
  const handleLocaleChange = (lng: string) => {
    i18n.changeLanguage(lng)
    setLocale(lng)
  }

  // Logout ----------------------------------------------------------------
  // Routes through the server-side /auth/logout call (via useLogout), not just
  // the local zustand flag -- otherwise the session cookies stay valid and
  // RequireAuth's session-probe silently re-authenticates the user right back in.
  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      onSuccess: () => navigate('/login'),
    })
  }

  // Theme / locale options
  const themeOptions: ToggleOption[] = [
    { value: 'light', label: t('settings.themeLight') },
    { value: 'dark', label: t('settings.themeDark') },
    { value: 'system', label: t('settings.themeSystem') },
  ]

  const localeOptions: ToggleOption[] = SUPPORTED_LOCALES.map((l) => ({
    value: l.code,
    label: l.label,
  }))

  const dateFormatOptions: ToggleOption[] = [
    { value: 'locale', label: t('settings.dateFormatLocale') },
    { value: 'iso', label: t('settings.dateFormatIso') },
  ]

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <PageHeader title={t('settings.title')} />

      {/* Appearance ------------------------------------------------------- */}
      <SectionCard
        icon={Paintbrush}
        title={t('settings.appearance')}
        description={t('settings.appearanceDescription')}
      >
        <div className="space-y-6">
          {/* Theme */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.theme')}</Label>
            <ToggleGroup
              options={themeOptions}
              value={theme}
              onChange={(v) => setTheme(v as Theme)}
            />
          </div>

          {/* Language */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.language')}</Label>
            <ToggleGroup
              options={localeOptions}
              value={resolveLocale(locale).code}
              onChange={handleLocaleChange}
            />
          </div>

          {/* Date format */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.dateFormat')}</Label>
            <ToggleGroup
              options={dateFormatOptions}
              value={dateFormat}
              onChange={(v) => setDateFormat(v as DateFormat)}
            />
          </div>
        </div>
      </SectionCard>

      {/* Account ---------------------------------------------------------- */}
      <SectionCard
        icon={User}
        title={t('settings.account')}
        description={t('settings.accountDescription')}
      >
        <div className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <Label className="text-sm font-medium shrink-0">
              {t('settings.username')}
            </Label>
            {editingUsername ? (
              <div className="flex flex-col items-end gap-1">
                <div className="flex items-center gap-1">
                  <Input
                    value={newUsername}
                    onChange={e => setNewUsername(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') saveUsername(); if (e.key === 'Escape') cancelEditUsername() }}
                    className="w-44"
                    autoFocus
                    disabled={usernameSaving}
                  />
                  <Button size="icon-sm" variant="ghost" onClick={saveUsername} disabled={usernameSaving}>
                    <Check className="size-4 text-green-600" />
                  </Button>
                  <Button size="icon-sm" variant="ghost" onClick={cancelEditUsername} disabled={usernameSaving}>
                    <X className="size-4" />
                  </Button>
                </div>
                {usernameError && <p className="text-xs text-destructive">{usernameError}</p>}
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{user?.username}</span>
                <Button size="icon-sm" variant="ghost" onClick={startEditUsername}>
                  <Pencil className="size-3.5" />
                </Button>
              </div>
            )}
          </div>
          <div className="flex justify-end">
            <Button variant="destructive" onClick={handleLogout} disabled={logoutMutation.isPending}>
              <LogOut className="mr-2 size-4" />
              {t('settings.logout')}
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* Security --------------------------------------------------------- */}
      <SectionCard
        icon={Shield}
        title={t('settings.security')}
        description={t('settings.securityDescription')}
      >
        <SecuritySection />
      </SectionCard>

      {/* Access keys & MCP ------------------------------------------------ */}
      <SectionCard
        icon={KeyRound}
        title={t('accessKeys.sectionTitle')}
        description={t('accessKeys.sectionDescription')}
      >
        <AccessKeysSection />
      </SectionCard>

      {/* Family ----------------------------------------------------------- */}
      <SectionCard
        icon={Users}
        title={t('settings.family', 'Family')}
        description={t('settings.familyDescription', 'Manage members and sharing settings')}
      >
        <button
          type="button"
          onClick={() => navigate('/settings/family')}
          className="flex w-full items-center justify-between rounded-lg p-2 text-sm font-medium transition-colors hover:bg-muted"
        >
          <span>{t('settings.familyManage', 'Manage family members & sharing')}</span>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>
      </SectionCard>

      {/* Admin (admin-only) ----------------------------------------------- */}
      {user?.role === 'ADMIN' && (
        <SectionCard
          icon={Shield}
          title={t('settings.adminSection')}
          description={t('settings.adminDescription')}
        >
          <button
            type="button"
            onClick={() => navigate('/admin')}
            className="flex w-full items-center justify-between rounded-lg p-2 text-sm font-medium transition-colors hover:bg-muted"
          >
            <span>{t('settings.adminButton')}</span>
            <ChevronRight className="size-4 text-muted-foreground" />
          </button>
        </SectionCard>
      )}

      {/* About ------------------------------------------------------------ */}
      <SectionCard
        icon={Globe}
        title={t('settings.about')}
        description={t('settings.aboutDescription')}
      >
        <div className="space-y-3 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">Picsou</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">
              {t('settings.version')}
            </span>
            <span className="font-medium">1.0.8</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">GitHub</span>
            <span className="font-medium">github.com/zoeille/picsou</span>
          </div>
        </div>
      </SectionCard>
    </div>
  )
}
