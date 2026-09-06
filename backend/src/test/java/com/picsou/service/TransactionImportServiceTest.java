package com.picsou.service;

import com.picsou.dto.ColumnMappingDto;
import com.picsou.dto.CsvDialectDto;
import com.picsou.dto.TransactionImportPreviewResponse;
import com.picsou.dto.TransactionImportRequest;
import com.picsou.dto.TransactionImportResultResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.imports.TransactionRowMapper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionImportServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock HoldingComputeService holdingComputeService;
    @Mock InstrumentFieldResolver instrumentFieldResolver;

    TransactionImportService service;

    @BeforeEach
    void setUp() {
        service = new TransactionImportService(accountRepository, transactionRepository,
            holdingComputeService, new TransactionRowMapper(instrumentFieldResolver), familyMemberRepository);
        lenient().when(familyMemberRepository.existsById(anyLong())).thenReturn(true);
    }

    private Account pea() {
        return Account.builder().id(2L).type(AccountType.PEA).currency("EUR").build();
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "trades.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String CSV =
        "date,side,ticker,quantity,price,fees\n" +
        "2024-01-15,BUY,AAPL,10,85.20,1.00\n" +
        "2024-06-02,SELL,AAPL,10,92.50,1.00\n";

    private ColumnMappingDto mapping() {
        return new ColumnMappingDto(0, 1, 2, null, 3, 4, 5, null, null);
    }

    private CsvDialectDto dialect() {
        return new CsvDialectDto(",", "DOT", "yyyy-MM-dd");
    }

    @Test
    void preview_detectsColumnsAndCachesToken() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));

        TransactionImportPreviewResponse preview = service.preview(2L, 10L, file(CSV));

        assertThat(preview.hasHeaderRow()).isTrue();
        assertThat(preview.detectedColumns())
            .containsExactly("date", "side", "ticker", "quantity", "price", "fees");
        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.fileToken()).isNotBlank();
        assertThat(preview.suggestedMapping().date()).isEqualTo(0);
        assertThat(preview.suggestedMapping().quantity()).isEqualTo(3);
        assertThat(preview.suggestedMapping().fees()).isEqualTo(5);
    }

    @Test
    void executeImport_savesRowsAndRecomputesOnce() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));

        String token = service.preview(2L, 10L, file(CSV)).fileToken();
        TransactionImportRequest req =
            new TransactionImportRequest(token, mapping(), dialect(), true, false, null);

        TransactionImportResultResponse result = service.executeImport(2L, 10L, req);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(holdingComputeService, times(1)).recomputeHoldings(any());
    }

    @Test
    void executeImport_reportsPerRowErrorsAndStillImportsValidRows() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));

        // second data row has a non-numeric quantity → skipped, first row imported
        String csv = "date,side,ticker,quantity,price,fees\n"
            + "2024-01-15,BUY,AAPL,10,85.20,1.00\n"
            + "2024-02-15,BUY,AAPL,oops,85.20,1.00\n";
        String token = service.preview(2L, 10L, file(csv)).fileToken();
        TransactionImportRequest req =
            new TransactionImportRequest(token, mapping(), dialect(), true, false, null);

        TransactionImportResultResponse result = service.executeImport(2L, 10L, req);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(3); // header=1, first data=2, bad=3
    }

    @Test
    void preview_headerOnlyFile_reportsZeroDataRows() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));

        TransactionImportPreviewResponse preview =
            service.preview(2L, 10L, file("date,side,ticker,quantity,price,fees\n"));

        assertThat(preview.hasHeaderRow()).isTrue();
        assertThat(preview.totalRows()).isZero();
        assertThat(preview.sampleRows()).isEmpty();
    }

    @Test
    void executeImport_expiredToken_throws() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        TransactionImportRequest req =
            new TransactionImportRequest("nope", mapping(), dialect(), true, false, null);

        assertThatThrownBy(() -> service.executeImport(2L, 10L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void executeImport_tokenBoundToAnotherAccount_throws() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        Account otherCto = Account.builder().id(3L).type(AccountType.COMPTE_TITRES).currency("EUR").build();
        when(accountRepository.findByIdAndMemberId(3L, 10L)).thenReturn(Optional.of(otherCto));
        when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));

        String token = service.preview(2L, 10L, file(CSV)).fileToken(); // bound to account 2
        TransactionImportRequest req =
            new TransactionImportRequest(token, mapping(), dialect(), true, false, null);

        assertThatThrownBy(() -> service.executeImport(3L, 10L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");

        assertThat(service.executeImport(2L, 10L, req).imported()).isEqualTo(2);
        verify(transactionRepository).saveAll(any());
    }

    @Test
    void executeImport_tokenBoundToAnotherMember_throws() {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        when(accountRepository.findByIdAndMemberId(2L, 11L)).thenReturn(Optional.of(pea()));
        when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));

        String token = service.preview(2L, 10L, file(CSV)).fileToken();
        TransactionImportRequest req =
            new TransactionImportRequest(token, mapping(), dialect(), true, false, null);

        assertThatThrownBy(() -> service.executeImport(2L, 11L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired or invalid");

        assertThat(service.executeImport(2L, 10L, req).imported()).isEqualTo(2);
        verify(transactionRepository).saveAll(any());
    }

    @Test
    void executeImport_concurrentReplayHasOnlyOneWriter() throws Exception {
        when(accountRepository.findByIdAndMemberId(2L, 10L)).thenReturn(Optional.of(pea()));
        when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));

        String token = service.preview(2L, 10L, file(CSV)).fileToken();
        TransactionImportRequest req =
            new TransactionImportRequest(token, mapping(), dialect(), true, false, null);
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        when(transactionRepository.saveAll(any())).thenAnswer(invocation -> {
            if (firstWrite.compareAndSet(true, false)) {
                firstWriteEntered.countDown();
                if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("First import did not receive its release signal");
                }
            }
            return invocation.getArgument(0);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<TransactionImportResultResponse> winner = executor.submit(
            () -> service.executeImport(2L, 10L, req));
        try {
            assertThat(firstWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.executeImport(2L, 10L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or invalid");
            verify(transactionRepository, times(1)).saveAll(any());
            verify(holdingComputeService, never()).recomputeHoldings(any());
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdown();
        }

        assertThat(winner.get(5, TimeUnit.SECONDS).imported()).isEqualTo(2);
        verify(holdingComputeService).recomputeHoldings(any());
    }

    @Test
    void preview_nonInvestmentAccount_throws() {
        Account checking = Account.builder().id(9L).type(AccountType.CHECKING).currency("EUR").build();
        when(accountRepository.findByIdAndMemberId(9L, 10L)).thenReturn(Optional.of(checking));

        assertThatThrownBy(() -> service.preview(9L, 10L, file("date\n2024-01-01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("investment");
    }

    @Test
    void preview_syncedInvestmentAccount_throws() {
        Account syncedCto = Account.builder()
            .id(4L)
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .isManual(false)
            .build();
        when(accountRepository.findByIdAndMemberId(4L, 10L)).thenReturn(Optional.of(syncedCto));

        assertThatThrownBy(() -> service.preview(4L, 10L, file(CSV)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manual");
    }

    @Test
    void preview_foreignAccount_throws() {
        when(accountRepository.findByIdAndMemberId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(99L, 10L, file("date\n2024-01-01")))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
