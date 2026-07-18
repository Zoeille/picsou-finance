# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # Run locally (needs PostgreSQL on :5432)
mvn test                                              # Run all tests
mvn test -Dtest=GoalServiceTest                       # Run a single test class
mvn package -DskipTests                               # Build JAR
```

Tests use H2 in-memory — no external database needed. The one exception is the Flyway
migration test, which needs real PostgreSQL (Testcontainers, Docker Engine ≥ 25.0); it
skips itself when Docker is unreachable, so the rest of the suite still runs.

## Package structure

```
com.picsou/
├── model/        JPA entities
├── repository/   Spring Data JPA interfaces
├── service/      Business logic
├── controller/   REST controllers (all routes under /api/)
├── dto/          Request/response records
├── port/         Abstractions for external providers
├── adapter/      Port implementations (Enable Banking, CoinGecko, Yahoo Finance)
├── config/       Spring beans: security, JWT, rate limiting, properties
└── exception/    GlobalExceptionHandler + custom exceptions
```

## Key patterns

**Ports & adapters:** External integrations hide behind `BankConnectorPort` and `PriceProviderPort`. To swap a provider, implement the port and swap the `@Primary` bean — controllers/services never import adapters directly.

**Auth flow:** `JwtAuthenticationFilter` reads the `access_token` HttpOnly cookie, validates the `tv` (token-version) claim against `AppUser.tokenVersion`, and sets the `SecurityContext`. `AuthController` issues and rotates access/refresh tokens; `MfaController` issues `mfa_challenge` JWTs and verifies TOTP; `PersistentTokenAuthFilter` re-issues access tokens from rotating "Remember Me" tokens. CSRF is disabled — `SameSite=Lax` cookies + JSON-only API surface provide equivalent protection (`Lax` rather than `Strict` for Safari iOS compatibility).

**Member-scoped authorization:** every controller resolves `UserContext.currentMemberId()` (or `currentMemberIdOverride()` for admin impersonation), and every service/repository scopes queries by `member_id`. Family-shared access goes through `SharingSettings` + `SharedResource`. Never query a repo without a member filter.

**Rate limiting:** `RateLimitConfig` configures Bucket4j buckets; the actual enforcement is in the controllers via annotations. Login: 5 attempts/15 min. Sync endpoints are also throttled.

**Scheduled tasks** (`SchedulerService`): daily balance snapshots and price cache refresh. `PriceService` holds a 15-minute in-memory cache to avoid hammering external APIs.

**Flyway owns the schema** — never use `ddl-auto: create/update`. Migration details and entity conventions: see [`docs/conventions/database.md`](../docs/conventions/database.md).

## Configuration

`application.yml` (prod), `application-dev.yml` (dev, enables SQL logging). All secrets from env vars — see `.env.example` at project root.

## Testing

Mockito unit tests (`@ExtendWith(MockitoExtension.class)`), `@DataJpaTest` with H2 for integration. Full conventions: see [`docs/conventions/testing.md`](../docs/conventions/testing.md).
