interface ApiErrorBody {
  detail?: string | null
  message?: string | null
}

function tryParseJson(s: string): ApiErrorBody | null {
  try { return JSON.parse(s) } catch { return null }
}

// Strings matching this leak server internals or are raw axios noise — they must
// never be shown to the user. Callers fall back to a friendly i18n string instead.
const LEAK_PATTERN = /Exception|\.java\b|\bjava\.|\borg\.|com\.picsou|Request failed with status code|stack ?trace/i

function isSafeMessage(s: string): boolean {
  return s.trim().length > 0 && !LEAK_PATTERN.test(s)
}

/**
 * Returns a user-safe message the backend explicitly sent (ProblemDetail `detail`,
 * a `message` field, or a JSON-embedded `message`), or `null` when nothing safe is
 * available. Anything that looks like a stack trace / class name / axios default is
 * rejected so it can be replaced by a friendly fallback.
 */
export function safeBackendMessage(err: unknown): string | null {
  const axErr = err as { response?: { data?: ApiErrorBody }; message?: string }

  const detail = axErr?.response?.data?.detail
  if (detail && typeof detail === 'string') {
    const jsonStart = detail.indexOf('{')
    if (jsonStart >= 0) {
      const parsed = tryParseJson(detail.slice(jsonStart))
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(detail)) return detail
  }

  const bodyMessage = axErr?.response?.data?.message
  if (bodyMessage && typeof bodyMessage === 'string' && isSafeMessage(bodyMessage)) {
    return bodyMessage
  }

  const msg = axErr?.message
  if (msg && typeof msg === 'string') {
    if (msg.startsWith('{')) {
      const parsed = tryParseJson(msg)
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(msg)) return msg
  }

  return null
}

export function extractErrorMessage(err: unknown, fallback = 'Une erreur est survenue'): string {
  return safeBackendMessage(err) ?? fallback
}

/** HTTP status of an axios-style error, or `undefined` when not available. */
export function getErrorStatus(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

/** Backend `detail` string of an axios-style error, or `undefined` when absent. */
export function getErrorDetail(err: unknown): string | undefined {
  const detail = (err as { response?: { data?: ApiErrorBody } })?.response?.data?.detail
  return typeof detail === 'string' ? detail : undefined
}

// formatApiError only ever calls t with a single key argument, so we type it as
// such. A looser `(key, fallback?: string)` signature would reject the real
// i18next TFunction, whose second positional arg is an options object / default
// value rather than a plain string — that mismatch is what broke the CI build.
type TFunc = (key: string) => string

/**
 * Maps any API error to a friendly, translated string.
 *
 * - 401 / 429 / 5xx → a generic translated message (the backend detail here is
 *   either absent or the deliberately-vague "An unexpected error occurred").
 * - 4xx → the backend's *specific* reason when it's user-safe (e.g. "Cannot delete
 *   the last administrator", "Username already taken"); otherwise a translated
 *   fallback (403 → "forbidden", others → `fallbackKey`).
 *
 * Never returns raw axios text or leaked internals — those are filtered by
 * {@link safeBackendMessage}.
 */
/**
 * Maps Trade Republic auth errors (credentials initiation / TAN completion) to
 * translated messages. TR rejections surface as 5xx from the backend proxy with
 * the upstream error code embedded in the `detail` string. Shared by
 * AddAccountModal and TradeRepublicTab so the mappings can't drift.
 */
export function formatTrAuthError(err: unknown, t: TFunc): string {
  const status = getErrorStatus(err)

  if (status === 429) return t('sync.tr.errors.tooManyAttempts')

  if (status === 500 || status === 502 || status === 503) {
    const detail = getErrorDetail(err) || ''
    if (detail.includes('authentication service is unavailable')) return t('sync.tr.errors.serviceUnavailable')
    if (detail.includes('VALIDATION_CODE_INVALID') || detail.includes('verification code is invalid')) return t('sync.tr.errors.invalidTan')
    if (detail.includes('NUMBER_INVALID')) return t('sync.tr.errors.invalidPhoneNumber')
    if (detail.includes('PIN_INVALID')) return t('sync.tr.errors.invalidPin')
    if (detail.includes('AUTHENTICATION_ERROR')) return t('sync.tr.errors.authenticationFailed')
    return t('sync.tr.errors.serverError')
  }

  if (status === 422) {
    const errors =
      (err as { response?: { data?: { errors?: Record<string, unknown> } } })?.response?.data
        ?.errors ?? {}
    if (errors.phoneNumber) return t('sync.tr.errors.phoneNumberRequired')
    if (errors.pin) return t('sync.tr.errors.pinRequired')
    return t('sync.tr.errors.validationFailed')
  }

  return extractErrorMessage(err, t('sync.tr.errors.unknownError'))
}

export function formatApiError(err: unknown, t: TFunc, fallbackKey = 'common.error'): string {
  const status = (err as { response?: { status?: number } })?.response?.status

  if (status === 401) return t('common.errors.unauthorized')
  if (status === 429) return t('common.errors.tooManyRequests')
  if (status && status >= 500) return t('common.errors.serverError')

  const safe = safeBackendMessage(err)
  if (safe) return safe

  if (status === 403) return t('common.errors.forbidden')
  return t(fallbackKey)
}
