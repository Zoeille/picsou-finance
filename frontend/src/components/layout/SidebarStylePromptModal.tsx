import { useTranslation } from 'react-i18next'
import { Users, Settings } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useAppStore, type SidebarStyle } from '@/stores/app-store'
import { cn } from '@/lib/utils'
import picsouLogo from '@/assets/horizontal-white-picsou.svg'
import { NAV_ITEMS } from './sidebar-nav-items'

const OPTIONS: { value: SidebarStyle; labelKey: string }[] = [
  { value: 'current', labelKey: 'settings.sidebarStyleCurrent' },
  { value: 'classic', labelKey: 'settings.sidebarStyleClassic' },
]

// Derived from AppSidebar's real NAV_ITEMS so the miniature can't drift out of
// sync with the actual sidebar. The Family item isn't in NAV_ITEMS (it's
// rendered separately in AppSidebar), so its icon (Users) is appended by hand.
const PREVIEW_NAV_ICONS = [...NAV_ITEMS.map((item) => item.icon), Users]

function SidebarPreview({ variant }: { variant: SidebarStyle }) {
  const isClassic = variant === 'classic'
  const navIcons = isClassic ? [...PREVIEW_NAV_ICONS, Settings] : PREVIEW_NAV_ICONS

  return (
    <div className="flex h-44 w-full gap-1.5 rounded-lg bg-muted/40 p-2" aria-hidden="true">
      <div className={cn('flex flex-col rounded-md bg-background p-1.5', isClassic ? 'w-[46%] items-center' : 'w-1/2')}>
        <img
          src={picsouLogo}
          alt=""
          className={cn('h-2 w-auto opacity-70 brightness-0 dark:invert', isClassic ? 'mb-2' : 'mb-1.5 self-start')}
        />
        <div className="flex w-full flex-col gap-1">
          {navIcons.map((Icon, i) => (
            <div
              key={i}
              className={cn('flex items-center gap-1 rounded-sm px-1 py-0.5', i === 0 && 'bg-muted')}
            >
              <Icon className="size-2.5 shrink-0 text-muted-foreground" />
              <div className="h-1 flex-1 rounded-full bg-muted" />
            </div>
          ))}
        </div>
        <div className="mt-auto flex w-full items-center gap-1 pt-1.5">
          <div className="size-3 shrink-0 rounded-full bg-primary/40" />
          {!isClassic && <div className="h-1 flex-1 rounded-full bg-muted" />}
          {!isClassic && <Settings className="size-2.5 shrink-0 text-muted-foreground" />}
        </div>
      </div>
      <div className="flex-1 space-y-1.5 rounded-md bg-background p-1.5">
        <div className="h-2.5 w-2/3 rounded-sm bg-muted" />
        <div className="h-8 rounded-sm bg-muted/60" />
      </div>
    </div>
  )
}

interface SidebarStylePromptModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Shown once — either right after setup or on a user's first normal login —
 * so the current/classic sidebar split (see AppSidebar.tsx) is an active
 * choice instead of a silent default. Picking an option closes the modal
 * immediately; dismissing it any other way (Escape, backdrop, close button)
 * also marks the prompt as seen, since re-showing it every session would be
 * more annoying than a silent default.
 */
export function SidebarStylePromptModal({ open, onOpenChange }: SidebarStylePromptModalProps) {
  const { t } = useTranslation()
  const sidebarStyle = useAppStore((s) => s.sidebarStyle)
  const setSidebarStyle = useAppStore((s) => s.setSidebarStyle)
  const setHasSeenSidebarStylePrompt = useAppStore((s) => s.setHasSeenSidebarStylePrompt)

  function handleOpenChange(next: boolean) {
    if (!next) setHasSeenSidebarStylePrompt(true)
    onOpenChange(next)
  }

  function choose(style: SidebarStyle) {
    setSidebarStyle(style)
    handleOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('sidebarStylePrompt.title')}</DialogTitle>
          <DialogDescription>{t('sidebarStylePrompt.description')}</DialogDescription>
        </DialogHeader>

        <div className="grid grid-cols-2 gap-3" role="radiogroup" aria-label={t('sidebarStylePrompt.title')}>
          {OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={sidebarStyle === option.value}
              onClick={() => choose(option.value)}
              className="flex flex-col gap-2 rounded-lg border border-border p-2 text-left transition-colors hover:border-primary/60 hover:bg-muted/40"
            >
              <SidebarPreview variant={option.value} />
              <span className="text-sm font-medium">{t(option.labelKey)}</span>
            </button>
          ))}
        </div>

        <p className="text-center text-xs text-muted-foreground">{t('sidebarStylePrompt.hint')}</p>
      </DialogContent>
    </Dialog>
  )
}
