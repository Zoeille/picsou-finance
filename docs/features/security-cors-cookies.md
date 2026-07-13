# Feature: CORS & Cookie Security

> Last updated: 2026-06-02 (forward-headers-strategy — fixes 403 behind HTTPS reverse proxy)

## Context

Picsou authenticates with HttpOnly JWT cookies. The CORS configuration controls which browser
origins may make credentialed requests to the API. Getting it wrong either blocks legitimate
clients or produces confusing silent failures. The standard deployment serves the SPA **and** the
API from the **same origin** (nginx serves the static build and reverse-proxies `/api`), so normal
login/setup traffic is same-origin and must never be subject to CORS at all.

## How it works

### CORS — dynamic, fail-closed

`DynamicCorsConfigurationSource` resolves the allowed origins **per request** (not at startup), so
the setup wizard's Security step takes effect without a container restart:

1. Look up `cors.allowed-origins` in the `app_setting` table (key
   `SetupService.KEY_CORS_ALLOWED_ORIGINS`). The wizard / admin page writes a CSV here.
2. If absent/empty, fall back to the `ALLOWED_ORIGINS` env var (`app.cors.allowed-origins`).
3. If still empty → return `null` ⇒ **fail closed** (no cross-origin allowed).

Origins are `setAllowedOrigins` (exact match, `allowCredentials: true`). **Wildcards are stripped**
(`sanitize()`): a `*` entry is incompatible with credentialed CORS, so an operator who sets `*`
fails closed rather than silently echoing every origin. Methods: `GET/POST/PUT/PATCH/DELETE/OPTIONS`.

An explicit `CorsFilter` bean (not the Security DSL) carries a `LoggingCorsProcessor` that logs the
origin on every rejection.

### Same-origin detection depends on the request scheme (the 403-behind-proxy trap)

Spring's `CorsUtils.isCorsRequest()` classifies same-vs-cross origin by comparing the `Origin`
header's **scheme + host + port** against `request.getScheme()/getServerName()/getServerPort()`.
All three equal ⇒ same-origin ⇒ CORS skipped entirely. Any differ ⇒ enforced as cross-origin.

Behind a TLS-terminating reverse proxy the browser sends `Origin: https://host`, but the
nginx→backend hop is plain HTTP. Without trusting forwarded headers the backend reports
`getScheme() == "http"`, the **scheme mismatches**, a genuinely same-origin request is treated as
cross-origin, and the fail-closed allow-list rejects it with **403**.

**Fix (1.0.2):** `server.forward-headers-strategy: framework` in `application.yml` activates
Spring's `ForwardedHeaderFilter`, which rewrites scheme/host/port from `X-Forwarded-*`. The backend
then sees `https`, recognizes the request as same-origin, and skips CORS. For this to work the
chain must carry the headers end to end:

- The **upstream TLS terminator** (Caddy, Traefik, Nginx Proxy Manager, Cloudflare Tunnel, …) must
  send `X-Forwarded-Proto: https`. All of the above do by default.
- Picsou's **own nginx** must not clobber it. It previously hardcoded `X-Forwarded-Proto $scheme`
  (always `http`, since that nginx listens on plain :8080). It now preserves the upstream value via
  a `map`, falling back to `$scheme` only when it is the edge. `X-Forwarded-Host`/`X-Forwarded-Port`
  are passed through **only when present** — never synthesized — so the backend derives the port
  from the scheme (443 for https) on the common standard-port deployment.

### Cookies

Auth tokens are HttpOnly cookies written by `AuthCookieWriter`:
```
access_token=...; Max-Age=900; Path=/; HttpOnly; SameSite=Lax[; Secure]
```
- `SameSite=Lax` — `Strict` dropped cookies on Safari iOS on certain navigations.
- The `Secure` flag is driven by `SecureCookieProvider` (DB `secure-cookies` setting → `SECURE_COOKIES`
  env, default `true`). Set `false` only when serving over plain HTTP. The wizard auto-detects this
  from `location.protocol`.

### Key files

| File | Role |
|------|------|
| `backend/src/main/java/com/picsou/config/DynamicCorsConfigurationSource.java` | Per-request origin resolution (DB → env → fail closed), wildcard stripping |
| `backend/src/main/java/com/picsou/config/SecurityConfig.java` | `.cors()`, explicit `CorsFilter` bean, CSRF disabled, filter chain |
| `backend/src/main/java/com/picsou/config/LoggingCorsProcessor.java` | Logs origin on CORS rejection |
| `backend/src/main/java/com/picsou/config/AuthCookieWriter.java` / `SecureCookieProvider.java` | Cookie construction + `Secure` flag |
| `backend/src/main/resources/application.yml` | `server.forward-headers-strategy: framework`, `app.cors.allowed-origins`, `app.secure-cookies` |
| `docker/nginx.conf`, `frontend/nginx.conf` | Preserve upstream `X-Forwarded-Proto/Host/Port` |
| `backend/src/main/java/com/picsou/controller/SetupController.java` (`/api/setup/security`) | Persists wizard's allowed origins |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `forward-headers-strategy: framework` | Backend reachable only via local nginx; no per-IP trusted-proxy config; no-op without headers | `native` (Tomcat RemoteIpValve — needs trusted-proxy IP ranges) |
| Preserve upstream `X-Forwarded-Proto`, never synthesize port | Proto alone lets the backend derive 443 from scheme; a synthesized `$server_port` (8080) re-breaks the match → 403 | Always set `X-Forwarded-Port $server_port` (wrong port → cross-origin) |
| Fail-closed empty default + wildcard stripping | `*` with credentials is unsafe and illegal in Spring | `ALLOWED_ORIGINS=*` default (previous behavior) |
| `setAllowedOrigins` (exact) | Credentialed CORS; origins come from the wizard | `setAllowedOriginPatterns("*")` |
| `SameSite=Lax` | Safari iOS compatibility | `SameSite=Strict` |

## Gotchas / Pitfalls

- **403 over HTTPS but works over HTTP = forwarded-headers problem, not a wrong origin.** The
  request is genuinely same-origin; the scheme just isn't reaching the backend. Check that the
  upstream proxy sends `X-Forwarded-Proto: https` and that nginx preserves it. Pre-seeding
  `ALLOWED_ORIGINS=https://host` only *masks* it (the exact origin then passes the cross-origin check).
- **Never set `X-Forwarded-Port` to nginx's `$server_port`.** On a standard `:443` deployment the
  browser's Origin has no explicit port (implies 443); forwarding port 8080 makes the backend
  perceive 8080 and mismatch → 403. Forward it only if the upstream actually sent one.
- **`docker-compose.override.yml` overrides `env_file`:** an `ALLOWED_ORIGINS` in its `environment:`
  block silently wins over `.env`. Check it first when CORS misbehaves in dev.
- **`Secure` flag on HTTP = redirect loop.** Cookies are dropped, `sessionStorage` stays set, the app
  loops dashboard ↔ `/login`. Fix: `SECURE_COOKIES=false`.
- **`PATCH` must be listed in allowed methods** — it is, but any new method needs adding.
- **`LoggingCorsProcessor` logs `getAllowedOriginPatterns()`** which is `null` here (we use
  `setAllowedOrigins`); the rejection log shows `patterns: null` — read the configured CSV instead.

## Tests

- `config/ForwardedHeadersCorsTest` — drives the real `ForwardedHeaderFilter` +
  `DynamicCorsConfigurationSource` + `LoggingCorsProcessor`: asserts an HTTPS same-origin request is
  **not** 403 with `X-Forwarded-Proto: https`, **is** 403 without it (the bug), and a genuine
  cross-origin request is still rejected.
- `config/DynamicCorsConfigurationSourceTest` — origin resolution (DB → env fallback, empty CSV, non-API routes).
- `config/SecureCookieProviderTest` — `Secure` flag resolution.

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md`,
  `docs/decisions/2026-04-23-first-launch-wizard.md`
- Feature: `docs/features/setup-wizard.md` (Security step writes the allowed origins)
