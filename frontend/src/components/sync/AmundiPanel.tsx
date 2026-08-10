import { useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  Lock,
  LogOut,
  PiggyBank,
  RefreshCw,
  ShieldCheck,
  Smartphone,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useAmundiStatus,
  useClearAmundiSession,
  useCompleteAmundiAuth,
  useInitiateAmundiAuth,
  useSyncAmundi,
} from "@/features/sync/hooks"
import { extractErrorMessage, getErrorCode, getErrorStatus } from "@/lib/errors"
import type { AmundiErrorCode } from "@/types/api"

/**
 * `AWAITING_APP` has no form: Amundi pushes a notification to the Mon Épargne
 * app and the completion request simply stays open until the user approves it.
 */
type AuthState = "IDLE" | "AWAITING_OTP" | "AWAITING_APP" | "ERROR"

interface AmundiPanelProps {
  onConnected?: () => void
}
export function AmundiPanel({ onConnected }: AmundiPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [login, setLogin] = useState("")
  const [password, setPassword] = useState("")
  const [code, setCode] = useState("")
  const [processId, setProcessId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const notifyConnectedOnSuccess = useRef(false)

  const status = useAmundiStatus()
  const initiate = useInitiateAmundiAuth()
  const complete = useCompleteAmundiAuth()
  const sync = useSyncAmundi()
  const logout = useClearAmundiSession()
  const { mutate: completeMutate } = complete

  const messageForCode = (value: AmundiErrorCode | string | null | undefined) => {
    switch (value) {
      case "INVALID_CREDENTIALS":
        return t("sync.amundi.errors.invalidCredentials")
      case "CAPTCHA_BLOCKED":
        return t("sync.amundi.errors.captchaBlocked")
      case "INVALID_OTP":
        return t("sync.amundi.errors.invalidCode")
      case "APP_VALIDATION_TIMEOUT":
        return t("sync.amundi.errors.appValidationTimeout")
      case "AUTH_ATTEMPT_EXPIRED":
        return t("sync.amundi.errors.authAttemptExpired")
      case "SESSION_EXPIRED":
        return t("sync.amundi.errors.sessionExpired")
      case "PORTFOLIO_INCOMPLETE":
        return t("sync.amundi.errors.portfolioIncomplete")
      case "UPSTREAM_FORMAT_CHANGED":
        return t("sync.amundi.errors.formatChanged")
      case "INVALID_DATA":
        return t("sync.amundi.errors.invalidData")
      case "UPSTREAM_UNAVAILABLE":
      case "INTERNAL_ERROR":
        return t("sync.amundi.errors.serverError")
      default:
        return null
    }
  }

  const formatError = (value: unknown) => {
    const httpStatus = getErrorStatus(value)
    if (httpStatus === 429) return t("sync.amundi.errors.tooManyAttempts")
    const codedMessage = messageForCode(getErrorCode(value))
    if (codedMessage) return codedMessage
    return extractErrorMessage(value, t("sync.amundi.errors.serverError"))
  }

  const resetAuth = () => {
    setAuthState("IDLE")
    setProcessId(null)
    setCode("")
  }

  const submitSecondFactor = (id: string, otp?: string) => {
    setError(null)
    completeMutate(
      { processId: id, code: otp },
      {
        onSuccess: (result) => {
          notifyConnectedOnSuccess.current = true
          resetAuth()
          setLogin("")
          setPassword("")
          if (result.syncStatus === "SUCCESS") {
            notifyConnectedOnSuccess.current = false
            onConnected?.()
          } else {
            void status.refetch()
          }
        },
        onError: (value) => {
          setError(formatError(value))
          setAuthState("ERROR")
          setProcessId(null)
          setCode("")
        },
      }
    )
  }

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing
  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.amundi.errors.serverError"))
      : null
  const statusError = status.isError ? formatError(status.error) : null
  const visibleError = error ?? backgroundError ?? statusError

  useEffect(() => {
    if (syncStatus !== "SUCCESS" || !notifyConnectedOnSuccess.current) return
    notifyConnectedOnSuccess.current = false
    onConnected?.()
  }, [onConnected, syncStatus])

  if (status.isLoading)
    return (
      <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
    )

  return (
    <div className="space-y-6">
      <Card size="sm">
        <CardContent className="py-4">
          <Badge
            className={
              connected
                ? "bg-green-500/10 text-green-600 dark:text-green-400"
                : undefined
            }
            variant={connected ? "default" : "outline"}
          >
            {connected
              ? t("sync.amundi.sessionActive")
              : t("sync.amundi.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">
            {t("sync.amundi.scope")}
          </p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.amundi.queued")
                : t("sync.amundi.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.amundi.syncSuccess")}
            </p>
          )}
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{visibleError}</p>
            {(error || statusError) && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setError(null)
                  resetAuth()
                  if (statusError) void status.refetch()
                }}
              >
                {t("common.retry")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {connected && (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => {
              setError(null)
              sync.mutate(undefined, {
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={requestingSync}
          >
            <RefreshCw
              className={requestingSync ? "animate-spin" : undefined}
            />
            {requestingSync
              ? t("sync.amundi.syncing")
              : t("sync.amundi.sync")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setError(null)
              logout.mutate(undefined, {
                onSuccess: () => {
                  notifyConnectedOnSuccess.current = false
                  resetAuth()
                },
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={logout.isPending}
          >
            <LogOut />
            {t("sync.amundi.clearSession")}
          </Button>
        </div>
      )}

      {!connected && authState === "IDLE" && (
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault()
            setError(null)
            initiate.mutate(
              { login, password },
              {
                onSuccess: (result) => {
                  setPassword("")
                  if (result.mfaRequired) {
                    if (!result.processId) {
                      setError(t("sync.amundi.errors.formatChanged"))
                      setAuthState("ERROR")
                      return
                    }
                    setProcessId(result.processId)
                    if (result.mfaType === "APP_PUSH") {
                      // Nothing to type: hold the completion request open
                      // while the user approves the push on their phone.
                      setAuthState("AWAITING_APP")
                      submitSecondFactor(result.processId)
                      return
                    }
                    setAuthState("AWAITING_OTP")
                    return
                  }
                  notifyConnectedOnSuccess.current = true
                  setLogin("")
                  setAuthState("IDLE")
                  void status.refetch()
                },
                onError: (value) => {
                  setPassword("")
                  setError(formatError(value))
                  setAuthState("ERROR")
                },
              }
            )
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="amundi-login">
                  <PiggyBank className="mr-1 inline-block size-4" />
                  {t("sync.amundi.login")}
                </Label>
                <Input
                  id="amundi-login"
                  autoComplete="username"
                  value={login}
                  onChange={(event) => setLogin(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="amundi-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.amundi.password")}
                </Label>
                <Input
                  id="amundi-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={initiate.isPending}>
                {initiate.isPending && <RefreshCw className="animate-spin" />}
                {initiate.isPending
                  ? t("sync.amundi.connecting")
                  : t("sync.amundi.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {!connected && authState === "AWAITING_APP" && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3 py-4">
            <Smartphone className="size-5 shrink-0 text-muted-foreground" />
            <div className="flex-1 space-y-1">
              <p className="text-sm">{t("sync.amundi.appValidationPrompt")}</p>
              <p className="text-xs text-muted-foreground">
                {t("sync.amundi.appValidationHint")}
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}

      {!connected && authState === "AWAITING_OTP" && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!processId) return
            submitSecondFactor(processId, code)
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <p className="text-sm text-muted-foreground">
                {t("sync.amundi.otpPrompt")}
              </p>
              <div className="space-y-2">
                <Label htmlFor="amundi-otp">
                  <ShieldCheck className="mr-1 inline-block size-4" />
                  {t("sync.amundi.otpCode")}
                </Label>
                <Input
                  id="amundi-otp"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={code}
                  onChange={(event) =>
                    setCode(event.target.value.replace(/\D/g, ""))
                  }
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={complete.isPending || code.length !== 6}
              >
                {complete.isPending && <RefreshCw className="animate-spin" />}
                {complete.isPending
                  ? t("sync.amundi.validating")
                  : t("sync.amundi.validate")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
