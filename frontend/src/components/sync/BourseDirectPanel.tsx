import { useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  BriefcaseBusiness,
  Lock,
  LogOut,
  RefreshCw,
  ShieldCheck,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useBourseDirectStatus,
  useClearBourseDirectSession,
  useCompleteBourseDirectAuth,
  useInitiateBourseDirectAuth,
  useSyncBourseDirect,
} from "@/features/sync/hooks"
import { extractErrorMessage, getErrorCode, getErrorStatus } from "@/lib/errors"
import type { BourseDirectErrorCode } from "@/types/api"

type AuthState = "IDLE" | "AWAITING_OTP" | "ERROR"

interface BourseDirectPanelProps {
  onConnected?: () => void
}
export function BourseDirectPanel({ onConnected }: BourseDirectPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [login, setLogin] = useState("")
  const [password, setPassword] = useState("")
  const [code, setCode] = useState("")
  const [processId, setProcessId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const notifyConnectedOnSuccess = useRef(false)

  const status = useBourseDirectStatus()
  const initiate = useInitiateBourseDirectAuth()
  const complete = useCompleteBourseDirectAuth()
  const sync = useSyncBourseDirect()
  const logout = useClearBourseDirectSession()

  const messageForCode = (
    code: BourseDirectErrorCode | string | null | undefined
  ) => {
    switch (code) {
      case "INVALID_CREDENTIALS":
        return t("sync.bourseDirect.errors.invalidCredentials")
      case "INVALID_OTP":
        return t("sync.bourseDirect.errors.invalidCode")
      case "AUTH_ATTEMPT_EXPIRED":
        return t("sync.bourseDirect.errors.authAttemptExpired")
      case "SESSION_EXPIRED":
        return t("sync.bourseDirect.errors.sessionExpired")
      case "PORTFOLIO_INCOMPLETE":
        return t("sync.bourseDirect.errors.portfolioIncomplete")
      case "UPSTREAM_FORMAT_CHANGED":
        return t("sync.bourseDirect.errors.formatChanged")
      case "INVALID_DATA":
        return t("sync.bourseDirect.errors.invalidData")
      case "UPSTREAM_UNAVAILABLE":
      case "INTERNAL_ERROR":
        return t("sync.bourseDirect.errors.serverError")
      default:
        return null
    }
  }

  const formatError = (value: unknown) => {
    const httpStatus = getErrorStatus(value)
    if (httpStatus === 429) return t("sync.bourseDirect.errors.tooManyAttempts")
    const codedMessage = messageForCode(getErrorCode(value))
    if (codedMessage) return codedMessage
    return extractErrorMessage(value, t("sync.bourseDirect.errors.serverError"))
  }

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing
  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.bourseDirect.errors.serverError"))
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
              ? t("sync.bourseDirect.sessionActive")
              : t("sync.bourseDirect.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">
            {t("sync.bourseDirect.scope")}
          </p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.bourseDirect.queued")
                : t("sync.bourseDirect.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.bourseDirect.syncSuccess")}
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
                  setAuthState("IDLE")
                  setProcessId(null)
                  setCode("")
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
              ? t("sync.bourseDirect.syncing")
              : t("sync.bourseDirect.sync")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setError(null)
              logout.mutate(undefined, {
                onSuccess: () => {
                  notifyConnectedOnSuccess.current = false
                  setAuthState("IDLE")
                  setProcessId(null)
                  setCode("")
                },
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={logout.isPending}
          >
            <LogOut />
            {t("sync.bourseDirect.clearSession")}
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
                      setError(t("sync.bourseDirect.errors.formatChanged"))
                      setAuthState("ERROR")
                      return
                    }
                    setProcessId(result.processId)
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
                <Label htmlFor="bourse-direct-login">
                  <BriefcaseBusiness className="mr-1 inline-block size-4" />
                  {t("sync.bourseDirect.login")}
                </Label>
                <Input
                  id="bourse-direct-login"
                  autoComplete="username"
                  value={login}
                  onChange={(event) => setLogin(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="bourse-direct-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.bourseDirect.password")}
                </Label>
                <Input
                  id="bourse-direct-password"
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
                  ? t("sync.bourseDirect.connecting")
                  : t("sync.bourseDirect.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {!connected && authState === "AWAITING_OTP" && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!processId) return
            setError(null)
            complete.mutate(
              { processId, code },
              {
                onSuccess: (result) => {
                  notifyConnectedOnSuccess.current = true
                  setAuthState("IDLE")
                  setLogin("")
                  setPassword("")
                  setProcessId(null)
                  setCode("")
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
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <p className="text-sm text-muted-foreground">
                {t("sync.bourseDirect.otpPrompt")}
              </p>
              <div className="space-y-2">
                <Label htmlFor="bourse-direct-otp">
                  <ShieldCheck className="mr-1 inline-block size-4" />
                  {t("sync.bourseDirect.otpCode")}
                </Label>
                <Input
                  id="bourse-direct-otp"
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
                  ? t("sync.bourseDirect.validating")
                  : t("sync.bourseDirect.validate")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
