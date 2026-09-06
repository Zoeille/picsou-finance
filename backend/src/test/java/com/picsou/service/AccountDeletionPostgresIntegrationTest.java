package com.picsou.service;

import com.picsou.dto.ReAuthDto;
import com.picsou.model.AccessKey;
import com.picsou.model.Account;
import com.picsou.model.AccountDeletionMode;
import com.picsou.model.AccountType;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserMfa;
import com.picsou.model.UserMfaRecoveryCode;
import com.picsou.model.UserRole;
import com.picsou.repository.AccessKeyRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.PersistentSessionRepository;
import com.picsou.repository.UserMfaRecoveryCodeRepository;
import com.picsou.repository.UserMfaRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIf("postgresAvailable")
@Import({
    FamilyService.class,
    PersistentSessionService.class,
    AccountDeletionPostgresIntegrationTest.TestBeans.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountDeletionPostgresIntegrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String EXTERNAL_JDBC_URL = System.getenv("PICSOU_TEST_POSTGRES_URL");

    static boolean postgresAvailable() {
        if (EXTERNAL_JDBC_URL != null && !EXTERNAL_JDBC_URL.isBlank()) {
            return true;
        }
        return dockerAvailable();
    }

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found.");
        }
        return available;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (EXTERNAL_JDBC_URL != null && !EXTERNAL_JDBC_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_JDBC_URL);
            registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("PICSOU_TEST_POSTGRES_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("PICSOU_TEST_POSTGRES_PASSWORD", ""));
        } else {
            POSTGRES.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.out-of-order", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @AfterAll
    static void stopPostgresContainer() {
        if ((EXTERNAL_JDBC_URL == null || EXTERNAL_JDBC_URL.isBlank()) && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired FamilyService familyService;
    @Autowired FamilyMemberRepository memberRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired UserMfaRepository userMfaRepository;
    @Autowired UserMfaRecoveryCodeRepository recoveryCodeRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired GoalRepository goalRepository;
    @Autowired AccessKeyRepository accessKeyRepository;
    @Autowired PersistentSessionRepository persistentSessionRepository;
    @Autowired PersistentSessionService persistentSessionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean ReAuthService reAuthService;

    private Fixture activeFixture;

    @Test
    void onlyActiveAdministrator_resetsWhenAnotherAdministratorIsInactive() {
        Fixture fixture = seedFixture();
        activeFixture = fixture;
        jdbcTemplate.update("UPDATE app_user SET is_activated = false WHERE id = ?", fixture.secondAdminId());

        assertThat(familyService.previewOwnAccountDeletion(fixture.firstAdminId()))
            .isEqualTo(AccountDeletionMode.RESET_LAST_ADMIN);
        assertThat(familyService.deleteOwnAccount(fixture.firstAdminId(), new ReAuthDto("test", null)))
            .isEqualTo(AccountDeletionMode.RESET_LAST_ADMIN);

        AppUser retained = userRepository.findByIdWithMember(fixture.firstAdminId()).orElseThrow();
        assertThat(retained.getUsername()).isEqualTo("admin-a");
        assertThat(retained.getPasswordHash()).isEqualTo("hash-a");
        assertThat(retained.isActivated()).isTrue();
        assertThat(retained.getTokenVersion()).isEqualTo(5L);
        assertThat(retained.getMember().getId()).isNotEqualTo(fixture.firstOldMemberId());
        assertThat(userMfaRepository.findByUserId(retained.getId()).orElseThrow().getTotpSecretEnc())
            .isEqualTo("secret-a");
        assertThat(memberRepository.existsById(fixture.firstOldMemberId())).isFalse();
        assertThat(accountRepository.existsById(fixture.firstAccountId())).isFalse();
        assertThat(goalRepository.existsById(fixture.firstGoalId())).isFalse();
        assertThat(userRepository.findById(fixture.secondAdminId()).orElseThrow().isActivated()).isFalse();
        assertThat(accountRepository.existsById(fixture.secondAccountId())).isTrue();
        assertThat(memberRepository.existsById(fixture.controlMemberId())).isTrue();
    }

    @Test
    void concurrentAdminSelfDeletions_leaveOneResetAdminAndEraseOnlyTheirData() throws Exception {
        Fixture fixture = seedFixture();
        activeFixture = fixture;
        ExecutorService executor = null;
        try {
            installDeleteDelayTrigger();
            executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Map<Long, Integer> backendPids = new ConcurrentHashMap<>();
            Future<AccountDeletionMode> first = executor.submit(
                () -> eraseWhenReleased(fixture.firstAdminId(), ready, start, backendPids));
            Future<AccountDeletionMode> second = executor.submit(
                () -> eraseWhenReleased(fixture.secondAdminId(), ready, start, backendPids));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(waitForBlockedBackend(backendPids, Duration.ofSeconds(5)))
                .as("one deletion transaction waits on the other administrator lock")
                .isTrue();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(
                    AccountDeletionMode.DELETE_ACCOUNT,
                    AccountDeletionMode.RESET_LAST_ADMIN);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            removeDeleteDelayTrigger();
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<AppUser> admins = userRepository.findAll().stream()
                .filter(user -> user.getRole() == UserRole.ADMIN)
                .toList();
            assertThat(admins).hasSize(1);

            AppUser survivor = userRepository.findByIdWithMember(admins.getFirst().getId()).orElseThrow();
            assertThat(survivor.getId())
                .isIn(fixture.firstAdminId(), fixture.secondAdminId());
            boolean firstAdminSurvived = survivor.getId().equals(fixture.firstAdminId());
            assertThat(survivor.getUsername())
                .isEqualTo(firstAdminSurvived ? "admin-a" : "admin-b");
            assertThat(survivor.getPasswordHash())
                .isEqualTo(firstAdminSurvived ? "hash-a" : "hash-b");
            assertThat(survivor.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(survivor.isActivated()).isTrue();
            assertThat(survivor.getTokenVersion()).isEqualTo(5L);
            assertThat(survivor.getActivationToken()).isNull();
            assertThat(survivor.getActivationTokenExpires()).isNull();
            assertThat(survivor.getMember().getId())
                .isNotIn(fixture.firstOldMemberId(), fixture.secondOldMemberId());
            assertThat(survivor.getMember().getDisplayName())
                .isEqualTo(firstAdminSurvived ? "Admin A" : "Admin B");
            assertThat(survivor.getMember().getAvatarColor())
                .isEqualTo(firstAdminSurvived ? "#111111" : "#222222");
            assertThat(survivor.getMember().isManaged()).isFalse();

            UserMfa survivorMfa = userMfaRepository.findByUserId(survivor.getId()).orElseThrow();
            assertThat(survivorMfa.getId()).isEqualTo(firstAdminSurvived
                ? fixture.firstMfaId()
                : fixture.secondMfaId());
            assertThat(survivorMfa.isEnabled()).isTrue();
            assertThat(survivorMfa.getTotpSecretEnc())
                .isEqualTo(firstAdminSurvived ? "secret-a" : "secret-b");
            assertThat(recoveryCodeRepository.findByUserMfaIdAndUsedAtIsNull(survivorMfa.getId()))
                .singleElement()
                .extracting(UserMfaRecoveryCode::getCodeHash)
                .isEqualTo(firstAdminSurvived ? "recovery-a" : "recovery-b");

            assertThat(memberRepository.existsById(fixture.firstOldMemberId())).isFalse();
            assertThat(memberRepository.existsById(fixture.secondOldMemberId())).isFalse();
            assertThat(memberRepository.existsById(fixture.controlMemberId())).isTrue();
            assertThat(userRepository.existsById(fixture.controlUserId())).isTrue();
            assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(fixture.controlMemberId()))
                .extracting(Account::getName)
                .containsExactly("Control account");
            assertThat(accountRepository.findAll()).extracting(Account::getName)
                .doesNotContain("Admin A account", "Admin B account");
            assertThat(goalRepository.findAll()).extracting(Goal::getName)
                .containsExactly("Control goal");

            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM account_ownership WHERE member_id IN (?, ?)",
                Integer.class, fixture.firstOldMemberId(), fixture.secondOldMemberId()))
                .isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM goal_contributor WHERE member_id IN (?, ?)",
                Integer.class, fixture.firstOldMemberId(), fixture.secondOldMemberId()))
                .isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shared_resource WHERE owner_member_id IN (?, ?)",
                Integer.class, fixture.firstOldMemberId(), fixture.secondOldMemberId()))
                .isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM goal_account WHERE goal_id IN (?, ?) OR account_id IN (?, ?)",
                Integer.class,
                fixture.firstGoalId(), fixture.secondGoalId(),
                fixture.firstAccountId(), fixture.secondAccountId()))
                .isZero();

            List<PersistentSession> sessions = persistentSessionRepository.findAll();
            assertThat(sessions).hasSize(1);
            assertThat(sessions.getFirst().getUser().getId()).isEqualTo(survivor.getId());
            assertThat(sessions.getFirst().getRevokedAt()).isNotNull();

            Integer retainedKeys = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM access_key WHERE created_by IN (?, ?)",
                Integer.class, fixture.firstAdminId(), fixture.secondAdminId());
            assertThat(retainedKeys).isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT value FROM app_setting WHERE setting_key = 'setup.state'", String.class))
                .isEqualTo("COMPLETE");
        });
    }

    @AfterEach
    void removeFixture() {
        try {
            removeDeleteDelayTrigger();
        } finally {
            Fixture fixture = activeFixture;
            if (fixture == null) {
                return;
            }
            activeFixture = null;
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.update("""
                    WITH deleted_users AS (
                        DELETE FROM app_user
                        WHERE id IN (?, ?, ?)
                        RETURNING member_id
                    )
                    DELETE FROM family_member
                    WHERE id IN (SELECT member_id FROM deleted_users)
                       OR id IN (?, ?, ?)
                    """,
                    fixture.firstAdminId(), fixture.secondAdminId(), fixture.controlUserId(),
                    fixture.firstOldMemberId(), fixture.secondOldMemberId(), fixture.controlMemberId());
                jdbcTemplate.update(
                    "UPDATE app_setting SET value = ? WHERE setting_key = 'setup.state'",
                    fixture.initialSetupState());
            });
        }
    }

    private AccountDeletionMode eraseWhenReleased(
        Long userId,
        CountDownLatch ready,
        CountDownLatch start,
        Map<Long, Integer> backendPids
    ) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            backendPids.put(userId, jdbcTemplate.queryForObject(
                "SELECT pg_backend_pid()", Integer.class));
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent deletion start barrier timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent deletion was interrupted", ex);
            }
            return familyService.deleteOwnAccount(userId, new ReAuthDto("test", null));
        }));
    }

    private boolean waitForBlockedBackend(
        Map<Long, Integer> backendPids,
        Duration timeout
    ) throws InterruptedException {
        List<Integer> pids = List.copyOf(backendPids.values());
        if (pids.size() != 2) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waitingLocks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE pid IN (?, ?) AND NOT granted",
                Integer.class, pids.get(0), pids.get(1));
            if (waitingLocks != null && waitingLocks > 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private Fixture seedFixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String initialSetupState = jdbcTemplate.queryForObject(
                "SELECT value FROM app_setting WHERE setting_key = 'setup.state'", String.class);
            FamilyMember firstMember = memberRepository.save(
                member("Admin A", "#111111", false));
            FamilyMember secondMember = memberRepository.save(
                member("Admin B", "#222222", false));
            FamilyMember controlMember = memberRepository.save(
                member("Control", "#333333", false));

            AppUser firstAdmin = userRepository.save(user(
                "admin-a", "hash-a", UserRole.ADMIN, firstMember, "reset-a"));
            AppUser secondAdmin = userRepository.save(user(
                "admin-b", "hash-b", UserRole.ADMIN, secondMember, "reset-b"));
            AppUser controlUser = userRepository.save(user(
                "control", "hash-control", UserRole.MEMBER, controlMember, null));
            userRepository.flush();

            UserMfa firstMfa = persistMfa(firstAdmin, "secret-a", "recovery-a");
            UserMfa secondMfa = persistMfa(secondAdmin, "secret-b", "recovery-b");

            Account firstAccount = accountRepository.save(account(firstMember, "Admin A account"));
            Account secondAccount = accountRepository.save(account(secondMember, "Admin B account"));
            Account controlAccount = accountRepository.save(account(controlMember, "Control account"));

            Goal firstGoal = goalRepository.save(goal(firstMember, "Admin A goal"));
            Goal secondGoal = goalRepository.save(goal(secondMember, "Admin B goal"));
            Goal controlGoal = goalRepository.save(goal(controlMember, "Control goal"));
            accountRepository.flush();
            goalRepository.flush();

            jdbcTemplate.update(
                "INSERT INTO account_ownership(account_id, member_id, share_percent) VALUES (?, ?, ?)",
                controlAccount.getId(), firstMember.getId(), 25);
            jdbcTemplate.update(
                "INSERT INTO account_ownership(account_id, member_id, share_percent) VALUES (?, ?, ?)",
                controlAccount.getId(), secondMember.getId(), 25);
            jdbcTemplate.update(
                "INSERT INTO goal_contributor(goal_id, member_id) VALUES (?, ?)",
                controlGoal.getId(), firstMember.getId());
            jdbcTemplate.update(
                "INSERT INTO goal_contributor(goal_id, member_id) VALUES (?, ?)",
                controlGoal.getId(), secondMember.getId());
            jdbcTemplate.update(
                "INSERT INTO shared_resource(owner_member_id, resource_type, resource_id) VALUES (?, ?, ?)",
                firstMember.getId(), "ACCOUNT", controlAccount.getId());
            jdbcTemplate.update(
                "INSERT INTO shared_resource(owner_member_id, resource_type, resource_id) VALUES (?, ?, ?)",
                secondMember.getId(), "GOAL", controlGoal.getId());
            jdbcTemplate.update(
                "INSERT INTO goal_account(goal_id, account_id) VALUES (?, ?)",
                firstGoal.getId(), controlAccount.getId());
            jdbcTemplate.update(
                "INSERT INTO goal_account(goal_id, account_id) VALUES (?, ?)",
                secondGoal.getId(), controlAccount.getId());
            jdbcTemplate.update(
                "INSERT INTO goal_account(goal_id, account_id) VALUES (?, ?)",
                controlGoal.getId(), firstAccount.getId());
            jdbcTemplate.update(
                "INSERT INTO goal_account(goal_id, account_id) VALUES (?, ?)",
                controlGoal.getId(), secondAccount.getId());
            jdbcTemplate.update(
                "UPDATE app_setting SET value = 'COMPLETE' WHERE setting_key = 'setup.state'");

            accessKeyRepository.save(accessKey(controlMember, firstAdmin.getId(), "psk_admin_a", "a"));
            accessKeyRepository.save(accessKey(controlMember, secondAdmin.getId(), "psk_admin_b", "b"));
            persistentSessionService.issue(firstAdmin, true, "test", "127.0.0.1");
            persistentSessionService.issue(secondAdmin, true, "test", "127.0.0.1");

            return new Fixture(
                firstAdmin.getId(), secondAdmin.getId(), firstMember.getId(), secondMember.getId(),
                controlMember.getId(), controlUser.getId(), firstMfa.getId(), secondMfa.getId(),
                firstAccount.getId(), secondAccount.getId(), firstGoal.getId(), secondGoal.getId(),
                initialSetupState);
        });
    }

    private FamilyMember member(String name, String color, boolean managed) {
        return FamilyMember.builder()
            .displayName(name)
            .avatarColor(color)
            .managed(managed)
            .build();
    }

    private AppUser user(
        String username,
        String passwordHash,
        UserRole role,
        FamilyMember member,
        String activationToken
    ) {
        return AppUser.builder()
            .username(username)
            .passwordHash(passwordHash)
            .member(member)
            .role(role)
            .activated(true)
            .activationToken(activationToken)
            .activationTokenExpires(activationToken == null
                ? null
                : Instant.now().plus(1, ChronoUnit.DAYS))
            .tokenVersion(4L)
            .build();
    }

    private UserMfa persistMfa(AppUser user, String secret, String recoveryCode) {
        UserMfa mfa = userMfaRepository.save(UserMfa.builder()
            .user(user)
            .enabled(true)
            .totpSecretEnc(secret)
            .enrolledAt(Instant.now())
            .build());
        recoveryCodeRepository.save(UserMfaRecoveryCode.builder()
            .userMfa(mfa)
            .codeHash(recoveryCode)
            .build());
        return mfa;
    }

    private Account account(FamilyMember member, String name) {
        return Account.builder()
            .member(member)
            .name(name)
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(BigDecimal.TEN)
            .isManual(true)
            .build();
    }

    private Goal goal(FamilyMember member, String name) {
        return Goal.builder()
            .member(member)
            .name(name)
            .targetAmount(BigDecimal.valueOf(1_000))
            .deadline(LocalDate.now().plusYears(1))
            .build();
    }

    private AccessKey accessKey(FamilyMember member, Long creatorId, String prefix, String hashChar) {
        return AccessKey.builder()
            .member(member)
            .createdBy(creatorId)
            .name("cross-member test key")
            .keyPrefix(prefix)
            .keyHash(hashChar.repeat(64))
            .scopes(Set.of("accounts:read"))
            .build();
    }

    private void installDeleteDelayTrigger() {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION account_deletion_test_delay()
            RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_sleep(0.8);
                RETURN OLD;
            END;
            $$
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER account_deletion_test_delay_trigger
            BEFORE DELETE ON app_user
            FOR EACH ROW EXECUTE FUNCTION account_deletion_test_delay()
            """);
    }

    private void removeDeleteDelayTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS account_deletion_test_delay_trigger ON app_user");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS account_deletion_test_delay()");
    }

    private record Fixture(
        Long firstAdminId,
        Long secondAdminId,
        Long firstOldMemberId,
        Long secondOldMemberId,
        Long controlMemberId,
        Long controlUserId,
        Long firstMfaId,
        Long secondMfaId,
        Long firstAccountId,
        Long secondAccountId,
        Long firstGoalId,
        Long secondGoalId,
        String initialSetupState
    ) {
    }
}
