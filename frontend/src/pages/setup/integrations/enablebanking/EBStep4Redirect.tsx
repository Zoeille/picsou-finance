import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, ExternalLink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'
import { useSetupFlowStore } from '@/stores/setup-flow-store'

interface Props {
  onNext: () => void
  onBack: () => void
}

export function EBStep4Redirect({ onNext, onBack }: Props) {
  const { t } = useTranslation()
  const draft = useSetupFlowStore((s) => s.ebDraft)

  const redirectUri =
    draft.redirectUri ||
    (typeof window !== 'undefined' ? `${window.location.origin}/sync/callback` : '')

  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(redirectUri)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* noop — user can Ctrl+C from the readonly input */
    }
  }

  return (
    <EBSubstepShell current={2} total={5}>
      <div className="space-y-6">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <ExternalLink className="h-6 w-6" />
          </span>
        </div>
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.redirect.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.redirect.body')}
          </p>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row">
          <input
            readOnly
            value={redirectUri}
            onFocus={(e) => e.currentTarget.select()}
            className="w-full rounded-lg border border-border/60 bg-muted/30 px-3 py-2 text-sm font-mono text-foreground"
          />
          <Button
            type="button"
            variant="outline"
            onClick={handleCopy}
            className="shrink-0 sm:w-auto"
          >
            {copied ? (
              <>
                <Check className="mr-2 h-4 w-4" />
                {t('setup.enablebanking.redirect.copied')}
              </>
            ) : (
              <>
                <Copy className="mr-2 h-4 w-4" />
                {t('setup.enablebanking.redirect.copy')}
              </>
            )}
          </Button>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            onClick={onBack}
            className="w-full sm:w-auto"
          >
            {t('setup.enablebanking.back')}
          </Button>
          <Button
            onClick={onNext}
            className="w-full rounded-full sm:w-auto"
          >
            {t('setup.enablebanking.redirect.addedCta')}
          </Button>
        </div>
      </div>
    </EBSubstepShell>
  )
}
