import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Download, KeyRound, Loader2, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import {
  useGenerateEnableBankingKeyPair,
  useImportEnableBankingPrivateKey,
} from '@/features/setup/hooks'

interface Props {
  onNext: () => void
  onBack: () => void
}

type Mode = 'generate' | 'import'

export function EBStep3Keypair({ onNext, onBack }: Props) {
  const { t } = useTranslation()
  const draft = useSetupFlowStore((s) => s.ebDraft)
  const updateEbDraft = useSetupFlowStore((s) => s.updateEbDraft)
  const generate = useGenerateEnableBankingKeyPair()
  const importKey = useImportEnableBankingPrivateKey()

  const [mode, setMode] = useState<Mode>('generate')
  const [copied, setCopied] = useState(false)
  const [privatePem, setPrivatePem] = useState('')
  const [importError, setImportError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  /**
   * Auto-generate on mount only in "generate" mode, and only if we don't
   * already have a public key in the draft (back-nav guard).
   */
  useEffect(() => {
    if (mode !== 'generate') return
    if (draft.publicKeyPem || generate.isPending || generate.isSuccess || generate.isError) return
    generate.mutate(undefined, {
      onSuccess: (data) => updateEbDraft({ publicKeyPem: data.publicKeyPem }),
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode])

  const pem = draft.publicKeyPem

  const handleCopy = async () => {
    if (!pem) return
    try {
      await navigator.clipboard.writeText(pem)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      const pre = document.getElementById('eb-public-pem')
      if (pre) {
        const range = document.createRange()
        range.selectNode(pre)
        const sel = window.getSelection()
        sel?.removeAllRanges()
        sel?.addRange(range)
      }
    }
  }

  const handleDownload = () => {
    if (!pem) return
    const blob = new Blob([pem], { type: 'application/x-pem-file' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'picsou-enablebanking-public.pem'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>): void => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => setPrivatePem((ev.target?.result as string) ?? '')
    reader.readAsText(file)
  }

  const handleImport = () => {
    if (!privatePem.trim()) return
    setImportError(null)
    importKey.mutate(
      { privatePem: privatePem.trim() },
      {
        onSuccess: (data) => updateEbDraft({ publicKeyPem: data.publicKeyPem }),
        onError: (err) => {
          const msg = (err as { response?: { data?: { detail?: string } } })
            ?.response?.data?.detail
          setImportError(msg ?? t('setup.enablebanking.keypair.importError'))
        },
      }
    )
  }

  const handleSwitchMode = (next: Mode) => {
    setMode(next)
    setPrivatePem('')
    setImportError(null)
    if (next === 'generate') {
      // Clear draft public key so the auto-generate effect fires cleanly.
      updateEbDraft({ publicKeyPem: null })
    } else {
      // Switching to import: clear any previously generated public key.
      updateEbDraft({ publicKeyPem: null })
    }
  }

  return (
    <EBSubstepShell current={4} total={5}>
      <div className="space-y-6">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <KeyRound className="h-6 w-6" />
          </span>
        </div>

        {/* Mode toggle */}
        <div className="flex rounded-lg border border-border/60 p-1 gap-1">
          <button
            type="button"
            onClick={() => handleSwitchMode('generate')}
            className={`flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
              mode === 'generate'
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {t('setup.enablebanking.keypair.modeGenerate')}
          </button>
          <button
            type="button"
            onClick={() => handleSwitchMode('import')}
            className={`flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
              mode === 'import'
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {t('setup.enablebanking.keypair.modeImport')}
          </button>
        </div>

        {/* ── GENERATE MODE ── */}
        {mode === 'generate' && (
          <>
            <div className="text-center space-y-2">
              <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
                {t('setup.enablebanking.keypair.title')}
              </h2>
              <p className="mx-auto max-w-md text-sm text-muted-foreground">
                {t('setup.enablebanking.keypair.body')}
              </p>
            </div>

            {generate.isPending && !pem && (
              <div className="flex flex-col items-center gap-2 py-8 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
                <p className="text-sm">{t('setup.enablebanking.keypair.generating')}</p>
              </div>
            )}

            {generate.isError && !pem && (
              <div className="space-y-3 rounded-xl border border-destructive/40 bg-destructive/5 p-4 text-center">
                <p className="text-sm text-destructive">
                  {t('setup.enablebanking.keypair.error')}
                </p>
                <Button variant="outline" size="sm" onClick={() => generate.mutate()}>
                  {t('setup.enablebanking.test.retry')}
                </Button>
              </div>
            )}

            {pem && (
              <>
                <pre
                  id="eb-public-pem"
                  className="max-h-64 overflow-auto rounded-xl border border-border/60 bg-muted/40 p-4 text-[11px] leading-snug font-mono whitespace-pre-wrap break-all"
                >
                  {pem}
                </pre>
                <div className="flex flex-col gap-2 sm:flex-row sm:justify-center">
                  <Button type="button" variant="outline" size="sm" onClick={handleCopy} className="w-full sm:w-auto">
                    {copied ? (
                      <><Check className="mr-2 h-4 w-4" />{t('setup.enablebanking.keypair.copied')}</>
                    ) : (
                      <><Copy className="mr-2 h-4 w-4" />{t('setup.enablebanking.keypair.copy')}</>
                    )}
                  </Button>
                  <Button type="button" variant="outline" size="sm" onClick={handleDownload} className="w-full sm:w-auto">
                    <Download className="mr-2 h-4 w-4" />
                    {t('setup.enablebanking.keypair.download')}
                  </Button>
                </div>
                <p className="text-center text-xs text-muted-foreground">
                  {t('setup.enablebanking.keypair.uploadInstruction')}
                </p>
              </>
            )}
          </>
        )}

        {/* ── IMPORT MODE ── */}
        {mode === 'import' && (
          <>
            <div className="text-center space-y-2">
              <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
                {t('setup.enablebanking.keypair.importTitle')}
              </h2>
              <p className="mx-auto max-w-md text-sm text-muted-foreground">
                {t('setup.enablebanking.keypair.importBody')}
              </p>
            </div>

            {!pem && (
              <div className="space-y-3">
                {/* File picker */}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".pem,application/x-pem-file,text/plain"
                  className="hidden"
                  onChange={handleFileChange}
                />
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => fileInputRef.current?.click()}
                >
                  <Upload className="mr-2 h-4 w-4" />
                  {t('setup.enablebanking.keypair.chooseFile')}
                </Button>

                {/* Or paste */}
                <p className="text-center text-xs text-muted-foreground">
                  {t('setup.enablebanking.keypair.orPaste')}
                </p>
                <textarea
                  value={privatePem}
                  onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setPrivatePem(e.target.value)}
                  placeholder={"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"}
                  className="w-full rounded-xl border border-border/60 bg-muted/40 p-3 font-mono text-[11px] leading-snug min-h-[140px] resize-none"
                />

                {importError && (
                  <p className="text-sm text-destructive">{importError}</p>
                )}

                <Button
                  className="w-full rounded-full"
                  disabled={!privatePem.trim() || importKey.isPending}
                  onClick={handleImport}
                >
                  {importKey.isPending ? (
                    <><Loader2 className="mr-2 h-4 w-4 animate-spin" />{t('setup.enablebanking.keypair.importing')}</>
                  ) : (
                    t('setup.enablebanking.keypair.importCta')
                  )}
                </Button>
              </div>
            )}

            {pem && (
              <>
                <p className="text-center text-sm text-muted-foreground">
                  {t('setup.enablebanking.keypair.importSuccess')}
                </p>
                <pre
                  id="eb-public-pem"
                  className="max-h-64 overflow-auto rounded-xl border border-border/60 bg-muted/40 p-4 text-[11px] leading-snug font-mono whitespace-pre-wrap break-all"
                >
                  {pem}
                </pre>
              </>
            )}
          </>
        )}

        {/* Navigation */}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
          <Button type="button" variant="ghost" onClick={onBack} className="w-full sm:w-auto">
            {t('setup.enablebanking.back')}
          </Button>
          <Button
            onClick={onNext}
            disabled={!pem}
            className="w-full rounded-full sm:w-auto"
          >
            {t('setup.enablebanking.keypair.uploadedCta')}
          </Button>
        </div>
      </div>
    </EBSubstepShell>
  )
}
