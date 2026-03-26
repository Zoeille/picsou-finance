# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # Run locally (needs PostgreSQL on :5432)
./mvnw test                                              # Run all tests
./mvnw test -Dtest=GoalServiceTest                       # Run a single test class
./mvnw package -DskipTests                               # Build JAR
```

Tests use H2 in-memory — no external database needed.

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

**Auth flow:** `JwtAuthenticationFilter` reads the `access_token` HttpOnly cookie and sets the `SecurityContext`. `AuthController` issues and rotates tokens. CSRF is disabled — SameSite=Strict cookies provide equivalent protection.

**Rate limiting:** `RateLimitConfig` configures Bucket4j buckets; the actual enforcement is in the controllers via annotations. Login: 5 attempts/15 min. Sync endpoints are also throttled.

**Scheduled tasks** (`SchedulerService`): daily balance snapshots and price cache refresh. `PriceService` holds a 15-minute in-memory cache to avoid hammering external APIs.

**Flyway owns the schema** — never use `ddl-auto: create/update`. New columns/tables always go in a new migration file `Vn__description.sql`.

## Configuration

`application.yml` — production defaults. `application-dev.yml` — enables SQL logging and devtools; activated with `-Dspring-boot.run.profiles=dev`.

All secrets come from environment variables. Required at startup: `JWT_SECRET`, `APP_USERNAME`, `APP_PASSWORD_HASH` (bcrypt, cost 12). Enable Banking variables are optional if bank sync is not used.

## Testing conventions

Existing tests use Mockito (`@ExtendWith(MockitoExtension.class)`) with `@Mock`/`@InjectMocks` — no Spring context loaded, no H2 involved. Service-layer unit tests follow this pattern. Integration tests that need JPA should use `@DataJpaTest` (H2 auto-configures).
