import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useAppStore, type SidebarStyle } from '@/stores/app-store'
import { cn } from '@/lib/utils'

const OPTIONS: { value: SidebarStyle; labelKey: string }[] = [
  { value: 'current', labelKey: 'settings.sidebarStyleCurrent' },
  { value: 'classic', labelKey: 'settings.sidebarStyleClassic' },
]

function SidebarPreview({ variant }: { variant: SidebarStyle }) {
  const isClassic = variant === 'classic'
  return (
    <div className="flex h-28 w-full gap-1.5 rounded-lg bg-muted/40 p-2" aria-hidden="true">
      <div className={cn('flex flex-col gap-1 rounded-md bg-background p-1.5', isClassic ? 'w-2/5' : 'w-1/2')}>
        {Array.from({ length: isClassic ? 5 : 3 }).map((_, i) => (
          <div key={i} className={cn('rounded-sm bg-muted', isClassic ? 'h-2.5' : 'h-3.5')} />
        ))}
        <div className="mt-auto flex items-center gap-1">
          <div className="size-3 shrink-0 rounded-full bg-primary/40" />
          {!isClassic && <div className="h-2 flex-1 rounded-sm bg-muted" />}
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

        <div className="grid grid-cols-2 gap-3">
          {OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
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
