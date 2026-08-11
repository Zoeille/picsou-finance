package com.picsou.port;

/**
 * Stable failure codes the {@code bourso-auth} sidecar returns in its RFC 7807
 * {@code detail} field, persisted on {@code bourso_session.last_sync_error} and
 * translated by the frontend.
 *
 * <p>There is no {@code INVALID_OTP}: BoursoBank's only supported second factor
 * is an app push, so no code is ever submitted. Adding one later needs a
 * migration — the column carries a CHECK constraint enumerating these values.
 */
public enum BoursoErrorCode {
    INVALID_CREDENTIALS,
    /** BoursoBank asked for an SMS or e-mail code, which this connector does not drive. */
    MFA_TYPE_UNSUPPORTED,
    APP_VALIDATION_TIMEOUT,
    AUTH_ATTEMPT_EXPIRED,
    SESSION_EXPIRED,
    PORTFOLIO_INCOMPLETE,
    UPSTREAM_FORMAT_CHANGED,
    UPSTREAM_UNAVAILABLE,
    INVALID_DATA,
    INTERNAL_ERROR
}
