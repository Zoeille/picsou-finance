import type { ComponentType } from 'react'
import { cn } from '@/lib/utils'

interface IntegrationCardProps {
  icon: ComponentType<{ className?: string }>
  title: string
  description: string
  checked: boolean
  onToggle: () => void
  disabled?: boolean
}

/**
 * Selection card used on the integration picker step. Designed to
 * double as a read-only "status card" in the post-install Settings →
 * Integrations page — pass {@code disabled} to lock the toggle there.
 *
 * The whole card is the hit target (not just the checkbox) so fat
 * fingers on mobile work. We still render a semantic {@code button}
 * with {@code role="switch"} for keyboard + screen-reader users.
 */
export function IntegrationCard({
  icon: Icon,
  title,
  description,
  checked,
  onToggle,
  disabled = false,
}: IntegrationCardProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-disabled={disabled}
      disabled={disabled}
      onClick={onToggle}
      className={cn(
        'w-full rounded-2xl border-2 p-4 text-left transition-[border-color,background-color,color,opacity] sm:p-5',
        'flex items-start gap-4',
        checked
          ? 'border-primary bg-primary/5'
          : 'border-border bg-card hover:border-primary/40',
        disabled && 'opacity-60 cursor-not-allowed'
      )}
    >
      <span
        className={cn(
          'shrink-0 rounded-xl p-2 transition-colors',
          checked ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
        )}
        aria-hidden="true"
      >
        <Icon className="h-6 w-6" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-medium text-sm sm:text-base leading-tight">{title}</p>
        <p className="mt-1 text-xs sm:text-sm text-muted-foreground">{description}</p>
      </div>
      <span
        className={cn(
          'mt-0.5 h-5 w-5 shrink-0 rounded-md border-2 flex items-center justify-center transition-colors',
          checked
            ? 'bg-primary border-primary text-primary-foreground'
            : 'border-muted-foreground/40'
        )}
        aria-hidden="true"
      >
        {checked && (
          <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M2 6l3 3 5-6" />
          </svg>
        )}
      </span>
    </button>
  )
}
