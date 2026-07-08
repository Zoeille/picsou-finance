# Convention: Testing

## Structure

There is **no** `unit/` or `integration/` directory split. All tests live flat under `src/test/java/com/picsou/`, mirroring the source package structure.

```
src/test/java/com/picsou/
├── service/      # 19 service test classes (GoalService, AccountService, FamilyService,
│                 #   MfaService, SecurityInsightService, HoldingCompute, …)
├── adapter/      # external-provider adapter tests
├── controller/   # MockMvc controller tests
├── config/       # security / config tests
└── export/       # GDPR export tests
```

## Unit tests

**Stack:** Mockito + AssertJ — no Spring context loaded.

```java
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;

    @InjectMocks GoalService goalService;

    @Test
    void progressCalculation_onTrack() {
        // arrange
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        when(accountService.toResponse(account)).thenReturn(/* ... */);

        // act
        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // assert
        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
    }
}
```

### Patterns

- **`@ExtendWith(MockitoExtension.class)`** with `@Mock` and `@InjectMocks` — no `MockitoAnnotations.openMocks()`.
- **Lombok builders for test data** — `Account.builder()`, `Goal.builder()` etc. No test fixtures or mother objects.
- **AssertJ** for all assertions (`assertThat`, `isEqualByComparingTo`). No JUnit `assertEquals`.
- **No Spring context** in unit tests — pure Mockito mocking.

### Naming

- **Class:** `[Class]Test` (e.g., `GoalServiceTest`).
- **Method:** descriptive, underscore-separated, e.g., `progressCalculation_onTrack`. Not strictly `should_xxx_when_yyy`.
- **One test per behavior** — a test may have multiple assertions on the same logical result, but does not test multiple scenarios.

## Integration tests

When JPA is needed, use `@DataJpaTest` with **H2 in-memory** (not Testcontainers).

```bash
# H2 auto-configures; no external database needed
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=SomeRepoTest
```

H2 is on the test classpath via `spring-boot-starter-test`. No Testcontainers dependency exists in the project.

## Frontend tests

- **Unit tests:** Vitest (`vitest`) with `@testing-library/react` and `jsdom`.
- **E2E tests:** Playwright (`@playwright/test`).
- Run commands:
  ```bash
  bunx vitest run         # unit tests
  bun run test:e2e        # E2E tests
  ```

## Running tests

Backend Maven runs enforce Java 21 during `validate`; set `JAVA_HOME` to a JDK 21
installation before running backend tests locally.

```bash
# Backend — all tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test

# Backend — single test class
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest

# Backend — single test method
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest#progressCalculation_onTrack
```

## Current coverage

The suite has **247 backend tests** across 35 test classes (service, adapter, controller, config, export). Service-layer unit tests dominate. When adding coverage, prioritize:

1. **Service-layer unit tests** — mock dependencies, test business logic.
2. **Repository custom queries** — `@DataJpaTest` for non-trivial JPQL.
3. **Controller integration tests** — MockMvc only when auth or validation flow needs verification.

## Don'ts

- **Never load Spring context in unit tests** — pure Mockito. Use `@DataJpaTest` only for JPA integration tests.
- **Never use `MockitoAnnotations.openMocks()`** — use `@ExtendWith(MockitoExtension.class)`.
- **Never use JUnit `assertEquals`** — always AssertJ (`assertThat`).
- **Never use `@Autowired` in tests** unless it's a `@DataJpaTest` integration test.
- **Never create test fixtures or "mother objects"** — use Lombok builders directly.
