import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { CheckCircle2, CircleAlert, Info, Loader2, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'
import { useTestEnableBanking } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from '../navigation'

interface Props {
  onBack: () => void
}

type Phase = 'idle' | 'running' | 'ok' | 'ko'

const KNOWN_CODES = new Set([
  'invalid_application_id',
  'invalid_key_id',
  'public_key_not_uploaded',
  'redirect_uri_mismatch',
  'network',
])

export function EBStep5Test({ onBack }: Props) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const test = useTestEnableBanking()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)
  const updateEbDraft = useSetupFlowStore((s) => s.updateEbDraft)

  const [phase, setPhase] = useState<Phase>('idle')
  const [failCode, setFailCode] = useState<string | null>(null)

  const run = () => {
    setPhase('running')
    setFailCode(null)
    test.mutate(undefined, {
      onSuccess: (result) => {
        if (result.ok) {
          setPhase('ok')
          updateEbDraft({ tested: true })
          markDone('enablebanking')
        } else {
          setPhase('ko')
          setFailCode(result.code)
        }
      },
      onError: () => {
        setPhase('ko')
        setFailCode('network')
      },
    })
  }

  const proceed = () => navigate(nextIntegrationRoute('enablebanking', selected))

  /**
   * Translate the backend's structured code to a friendly hint. Any code
   * we don't recognize falls back to the `unknown` message — we never dump
   * a raw code at the user; it's meaningless noise to a self-hoster.
   */
  const hintKey =
    failCode && KNOWN_CODES.has(failCode)
      ? `setup.enablebanking.test.hints.${failCode}`
      : 'setup.enablebanking.test.hints.unknown'

  return (
    <EBSubstepShell current={5} total={5}>
      <div className="space-y-6">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <Zap className="h-6 w-6" />
          </span>
        </div>
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.test.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.test.body')}
          </p>
        </div>

        {phase === 'idle' && (
          <>
            <div
              className="flex items-start gap-3 rounded-2xl border border-amber-500/40 bg-amber-500/5 p-4 text-sm text-amber-900 dark:text-amber-200"
              role="note"
            >
              <Info className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              <p>{t('setup.enablebanking.test.activationNotice')}</p>
            </div>
            <div className="flex justify-center">
              <Button
                onClick={run}
                className="w-full rounded-full"
              >
                {t('setup.enablebanking.test.run')}
              </Button>
            </div>
          </>
        )}

        {phase === 'running' && (
          <div className="flex flex-col items-center gap-2 py-6 text-muted-foreground">
            <Loader2 className="h-6 w-6 animate-spin" />
            <p className="text-sm">{t('setup.enablebanking.test.running')}</p>
          </div>
        )}

        {phase === 'ok' && (
          <div className="space-y-4 rounded-2xl border border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
            <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
            <p className="text-lg font-medium">{t('setup.enablebanking.test.success')}</p>
            <Button
              onClick={proceed}
              className="w-full rounded-full"
            >
              {t('setup.enablebanking.continue')}
            </Button>
          </div>
        )}

        {phase === 'ko' && (
          <div
            role="alert"
            className="space-y-4 rounded-2xl border border-destructive/40 bg-destructive/5 p-6"
          >
            <div className="flex items-start gap-3">
              <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
              <p className="text-sm text-destructive">{t(hintKey)}</p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
              <Button
                type="button"
                variant="outline"
                onClick={onBack}
                className="w-full sm:w-auto"
              >
                {t('setup.enablebanking.test.backToPrevious')}
              </Button>
              <Button
                type="button"
                onClick={run}
                className="w-full rounded-full sm:w-auto"
              >
                {t('setup.enablebanking.test.retry')}
              </Button>
            </div>
          </div>
        )}

        {phase !== 'ok' && (
          <div className="flex flex-col gap-2 sm:flex-row sm:justify-start">
            <Button
              type="button"
              variant="ghost"
              onClick={onBack}
              className="w-full sm:w-auto"
            >
              {t('setup.enablebanking.back')}
            </Button>
          </div>
        )}
      </div>
    </EBSubstepShell>
  )
}
