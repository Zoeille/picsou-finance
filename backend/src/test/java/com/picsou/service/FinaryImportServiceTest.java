package com.picsou.service;

import com.picsou.dto.FinaryAccountMapping;
import com.picsou.dto.FinaryImportRequest;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryImportServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;

    @Test
    void executeImport_concurrentReplayHasOnlyOneWriter() throws Exception {
        FinaryImportService service = new FinaryImportService(
            accountRepository,
            balanceSnapshotRepository,
            transactionRepository,
            familyMemberRepository,
            persistenceHelper
        );
        FamilyMember member = FamilyMember.builder().id(MEMBER_ID).displayName("Owner").build();
        when(familyMemberRepository.existsById(anyLong())).thenReturn(true);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(10L);
            if (firstWrite.compareAndSet(true, false)) {
                firstWriteEntered.countDown();
                if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("First import did not receive its release signal");
                }
            }
            return account;
        });

        String token = service.preview(xlsxWithOneCheckingAccount(), MEMBER_ID).fileToken();
        FinaryImportRequest request = new FinaryImportRequest(List.of(new FinaryAccountMapping(
            null,
            "Everyday account",
            "Checkings",
            FinaryMappingAction.CREATE_NEW,
            null,
            new NewAccountDetails("Everyday account", AccountType.CHECKING, "Finary", "EUR", null)
        )), token);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<FinaryImportResultResponse> winner = executor.submit(
            () -> service.executeImport(request, MEMBER_ID));
        try {
            assertThat(firstWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.executeImport(request, MEMBER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or invalid");
            verify(accountRepository, times(1)).save(any(Account.class));
            verify(persistenceHelper, never()).reconstructSnapshots(any(), any(), any());
            verify(persistenceHelper, never()).importTransactions(any(), any(), any());
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdown();
        }

        assertThat(winner.get(5, TimeUnit.SECONDS).accountsCreated()).isEqualTo(1);
        verify(persistenceHelper, times(1)).reconstructSnapshots(any(), any(), any());
        verify(persistenceHelper, times(1)).importTransactions(any(), any(), any());
    }

    private MockMultipartFile xlsxWithOneCheckingAccount() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Checkings");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Institution");
            header.createCell(2).setCellValue("Balance");
            header.createCell(3).setCellValue("Currency");
            var account = sheet.createRow(1);
            account.createCell(0).setCellValue("Everyday account");
            account.createCell(1).setCellValue("Bank");
            account.createCell(2).setCellValue(100.0);
            account.createCell(3).setCellValue("EUR");
            workbook.write(output);
            return new MockMultipartFile(
                "file",
                "finary.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray()
            );
        }
    }
}
