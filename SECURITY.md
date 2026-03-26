# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest  | ✅        |

## Reporting a Vulnerability

If you discover a security vulnerability, please **do not open a public GitHub issue**.

Instead, report it privately:
- Open a [GitHub Security Advisory](../../security/advisories/new) (preferred)
- Or email the maintainer directly (see profile)

Please include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

You will receive a response within 72 hours. We aim to patch critical vulnerabilities within 7 days.

## Security Design Notes

- JWT stored in `HttpOnly; Secure; SameSite=Strict` cookies (mitigates XSS token theft)
- Refresh token rotation on every use (mitigates token replay)
- Rate limiting on `/api/auth/login` (5 attempts / 15 min) and `/api/sync/**`
- All secrets loaded via environment variables — never hardcoded
- GoCardless credentials are never stored in the database
- No PII or financial balances in application logs
- Dependencies monitored via GitHub Dependabot
