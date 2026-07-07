import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { HelloGreeting } from './HelloGreeting'
import { useSetupStatus } from '@/features/setup/hooks'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'

/**
 * Step 0 of the wizard — the signature moment. After the Hello greeting
 * cycles through 12 locales, the welcome hero fades in with the single
 * "Get started" CTA. A tab-style language toggle in the top-left lets
 * the user switch languages mid-wizard without losing state
 * (i18next re-renders translations in place).
 */
export function SetupStepIntro() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'greeting' | 'content'>('greeting')
  const { data: setupStatus } = useSetupStatus()

  const activeLocale = resolveLocale(i18n.language)

  return (
    <div className="relative">
      {/* Tab-style language toggle — top-left of the wizard viewport. */}
      <div className="fixed left-4 top-4 z-40 sm:left-6 sm:top-6">
        <div
          role="tablist"
          aria-label={t('setup.intro.language')}
          className="inline-flex items-center rounded-full border border-border/60 bg-background/80 p-0.5 backdrop-blur-md shadow-sm"
        >
          {SUPPORTED_LOCALES.map((locale) => (
            <button
              key={locale.code}
              role="tab"
              type="button"
              aria-selected={activeLocale.code === locale.code}
              onClick={() => i18n.changeLanguage(locale.code)}
              className={cn(
                'rounded-full px-3 py-1 text-xs font-medium transition-colors',
                activeLocale.code === locale.code
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {locale.label}
            </button>
          ))}
        </div>
      </div>

      {phase === 'greeting' ? (
        <>
          <HelloGreeting onFinish={() => setPhase('content')} />
          <div className="mt-2 flex justify-center">
            <button
              type="button"
              onClick={() => setPhase('content')}
              aria-label={t('setup.intro.skipAria')}
              className="rounded-full px-3 py-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              {t('setup.intro.skip')}
            </button>
          </div>
        </>
      ) : (
        <div className="text-center space-y-8 animate-hello-in">
          <p className="text-xs font-semibold tracking-[0.2em] text-muted-foreground">
            {t('setup.intro.surtitle')}
          </p>
          <h1 className="text-4xl sm:text-5xl font-semibold tracking-tight leading-[1.05]">
            {t('setup.intro.title')}
          </h1>
          <p className="mx-auto max-w-md text-base sm:text-lg text-muted-foreground">
            {t('setup.intro.subtitle')}
          </p>
          <div className="pt-4">
            <Button
              size="lg"
              onClick={() =>
                navigate(
                  setupStatus?.state === 'IN_PROGRESS' ? '/setup/security' : '/setup/admin'
                )
              }
              className="rounded-full px-8 transition-transform hover:scale-[1.02]"
            >
              {t('setup.intro.cta')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
