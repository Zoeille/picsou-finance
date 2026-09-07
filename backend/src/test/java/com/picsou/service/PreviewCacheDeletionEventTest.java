package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.ColumnMappingDto;
import com.picsou.dto.CsvDialectDto;
import com.picsou.dto.FinaryImportRequest;
import com.picsou.dto.TransactionImportRequest;
import com.picsou.exception.SyncException;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.finary.client.FinaryApiClient;
import com.picsou.imports.TransactionRowMapper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinarySession;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.TransactionRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the actual {@code AFTER_COMMIT} listener wiring for every member-bound preview cache.
 */
@SpringJUnitConfig(PreviewCacheDeletionEventTest.TestConfig.class)
class PreviewCacheDeletionEventTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long ACCOUNT_ID = 2L;

    private static final String CSV = "date,side,ticker,quantity,price,fees\n";

    private static final ColumnMappingDto MAPPING =
        new ColumnMappingDto(0, 1, 2, null, 3, 4, 5, null, null);
    private static final CsvDialectDto DIALECT = new CsvDialectDto(",", "DOT", "yyyy-MM-dd");

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired FinaryApiSyncService finaryApiSyncService;
    @Autowired FinaryImportService finaryImportService;
    @Autowired TransactionImportService transactionImportService;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired FinarySessionRepository finarySessionRepository;
    @Autowired CryptoEncryption encryption;
    @Autowired FinaryApiClient finaryApiClient;

    @BeforeEach
    void setUp() {
        FamilyMember member = FamilyMember.builder().id(MEMBER_ID).displayName("Owner").build();
        Account account = Account.builder()
            .id(ACCOUNT_ID)
            .type(AccountType.PEA)
            .currency("EUR")
            .isManual(true)
            .build();
        FinarySession session = FinarySession.builder()
            .member(member)
            .email("enc-email")
            .password("enc-password")
            .status("CONNECTED")
            .build();

        when(familyMemberRepository.existsById(anyLong())).thenReturn(true);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        when(finarySessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-password")).thenReturn("password");
        when(finaryApiClient.authenticate("user@example.com", "password", null)).thenReturn("jwt");
        when(finaryApiClient.fetchOrganizationContext("jwt"))
            .thenReturn(new FinaryApiClient.OrgContext("org", "membership"));
        when(finaryApiClient.fetchCategoryAccounts(eq("jwt"), any(), anyString())).thenReturn(List.of());
        when(finaryApiClient.fetchLoans("jwt")).thenReturn(List.of());
        when(finaryApiClient.fetchCategoryTransactions(eq("jwt"), any(), anyString(), anyInt(), anyInt()))
            .thenReturn(List.of());
    }

    @Test
    void committedMemberDeletion_purgesEveryPreviewCache() throws IOException {
        PreviewTokens tokens = registerPreviews();

        inTransaction(() -> eventPublisher.publishEvent(new MemberDataDeletedEvent(MEMBER_ID)));

        assertThatThrownBy(() -> finaryApiSyncService.execute(tokens.api(), List.of(), MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("expired or invalid");
        assertThatThrownBy(() -> finaryImportService.executeImport(
            new FinaryImportRequest(List.of(), tokens.xlsx()), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired or invalid");
        assertThatThrownBy(() -> transactionImportService.executeImport(
            ACCOUNT_ID, MEMBER_ID, transactionRequest(tokens.csv())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired or invalid");
    }

    @Test
    void rolledBackMemberDeletion_keepsEveryPreviewCache() throws IOException {
        PreviewTokens tokens = registerPreviews();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new MemberDataDeletedEvent(MEMBER_ID));
            status.setRollbackOnly();
        });

        assertThat(finaryApiSyncService.execute(tokens.api(), List.of(), MEMBER_ID).accountsCreated()).isZero();
        assertThat(finaryImportService.executeImport(
            new FinaryImportRequest(List.of(), tokens.xlsx()), MEMBER_ID).accountsCreated()).isZero();
        assertThat(transactionImportService.executeImport(
            ACCOUNT_ID, MEMBER_ID, transactionRequest(tokens.csv())).imported()).isZero();
    }

    private PreviewTokens registerPreviews() throws IOException {
        return new PreviewTokens(
            finaryApiSyncService.preview(null, MEMBER_ID).fileToken(),
            finaryImportService.preview(emptyFinaryWorkbook(), MEMBER_ID).fileToken(),
            transactionImportService.preview(ACCOUNT_ID, MEMBER_ID, csvFile()).fileToken()
        );
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockMultipartFile emptyFinaryWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return new MockMultipartFile(
                "file", "finary.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
        }
    }

    private MockMultipartFile csvFile() {
        return new MockMultipartFile("file", "trades.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
    }

    private TransactionImportRequest transactionRequest(String token) {
        return new TransactionImportRequest(token, MAPPING, DIALECT, true, false, null);
    }

    private record PreviewTokens(String api, String xlsx, String csv) {}

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean FamilyMemberRepository familyMemberRepository() { return mock(FamilyMemberRepository.class); }
        @Bean AccountRepository accountRepository() { return mock(AccountRepository.class); }
        @Bean BalanceSnapshotRepository balanceSnapshotRepository() { return mock(BalanceSnapshotRepository.class); }
        @Bean TransactionRepository transactionRepository() { return mock(TransactionRepository.class); }
        @Bean FinarySessionRepository finarySessionRepository() { return mock(FinarySessionRepository.class); }
        @Bean CryptoEncryption encryption() { return mock(CryptoEncryption.class); }
        @Bean FinaryApiClient finaryApiClient() { return mock(FinaryApiClient.class); }
        @Bean HoldingComputeService holdingComputeService() { return mock(HoldingComputeService.class); }
        @Bean InstrumentFieldResolver instrumentFieldResolver() { return mock(InstrumentFieldResolver.class); }

        @Bean
        FinaryPersistenceHelper finaryPersistenceHelper(
            BalanceSnapshotRepository balanceSnapshotRepository,
            TransactionRepository transactionRepository
        ) {
            return new FinaryPersistenceHelper(balanceSnapshotRepository, transactionRepository);
        }

        @Bean
        FinaryImportService finaryImportService(
            AccountRepository accountRepository,
            BalanceSnapshotRepository balanceSnapshotRepository,
            TransactionRepository transactionRepository,
            FamilyMemberRepository familyMemberRepository,
            FinaryPersistenceHelper finaryPersistenceHelper
        ) {
            return new FinaryImportService(accountRepository, balanceSnapshotRepository, transactionRepository,
                familyMemberRepository, finaryPersistenceHelper);
        }

        @Bean
        TransactionImportService transactionImportService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            HoldingComputeService holdingComputeService,
            InstrumentFieldResolver instrumentFieldResolver,
            FamilyMemberRepository familyMemberRepository
        ) {
            return new TransactionImportService(accountRepository, transactionRepository, holdingComputeService,
                new TransactionRowMapper(instrumentFieldResolver), familyMemberRepository);
        }

        @Bean
        FinaryApiSyncService finaryApiSyncService(
            FinaryApiClient finaryApiClient,
            CryptoEncryption encryption,
            AccountRepository accountRepository,
            FamilyMemberRepository familyMemberRepository,
            FinarySessionRepository finarySessionRepository,
            FinaryPersistenceHelper finaryPersistenceHelper
        ) {
            return new FinaryApiSyncService(finaryApiClient, encryption, accountRepository,
                familyMemberRepository, finarySessionRepository, finaryPersistenceHelper);
        }
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Transaction synchronization is managed by the superclass.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No resource is needed: this test verifies transaction callback timing only.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No resource is needed: this test verifies transaction callback timing only.
        }
    }
}
