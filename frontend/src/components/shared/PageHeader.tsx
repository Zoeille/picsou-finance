import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { localeFromLanguage, todayLabel } from '@/lib/utils'

interface PageHeaderProps {
  surtitle?: string
  title: string
  actions?: React.ReactNode
}

export function PageHeader({ surtitle, title, actions }: PageHeaderProps) {
  const { i18n } = useTranslation()
  const [headerDate] = useState(() => new Date())
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const defaultSurtitle = todayLabel(locale, headerDate)
  const resolvedSurtitle = surtitle ?? defaultSurtitle

  return (
    <div className="mb-6 flex items-start justify-between gap-4">
      <div className="min-w-0">
        {resolvedSurtitle && (
          <p className="text-[13px] text-muted-foreground mb-1" style={{ fontWeight: 500 }}>
            {resolvedSurtitle}
          </p>
        )}
        <h1 className="truncate text-[28px] text-foreground" style={{ fontWeight: 700, lineHeight: 1.2 }}>
          {title}
        </h1>
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2 pt-6">{actions}</div>}
    </div>
  )
}
