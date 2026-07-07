package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.AccountData;
import com.picsou.port.BankConnectorPort.InstitutionData;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;

    @InjectMocks SyncService syncService;

    /**
     * initiateConnection resolves the logo itself from the server-side institution
     * catalog (matched by exact institutionId) -- there is no client-supplied logoUrl
     * to trust or persist.
     */
    @Test
    void initiateConnection_resolvesLogoUrlServerSideByExactInstitutionId() {
        Long memberId = 5L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

        when(bankConnector.initiateConnection("BNP_PARIBAS::FR"))
            .thenReturn(new BankConnectorPort.InitiateResult("auth-1", "https://auth.example/link"));

        InstitutionData wrongCountry = new InstitutionData("BNP_PARIBAS::BE", "BNP Paribas", "GEBABEBB",
            "https://logos.example/bnp-be.png", "BE");
        InstitutionData exact = new InstitutionData("BNP_PARIBAS::FR", "BNP Paribas", "BNPAFRPP",
            "https://logos.example/bnp-fr.png", "FR");
        when(bankConnector.searchInstitutions("BNP Paribas", "FR")).thenReturn(List.of(wrongCountry, exact));

        when(requisitionRepository.save(any(Requisition.class))).thenAnswer(inv -> inv.getArgument(0));

        syncService.initiateConnection("BNP_PARIBAS::FR", "BNP Paribas", memberId);

        ArgumentCaptor<Requisition> captor = ArgumentCaptor.forClass(Requisition.class);
        verify(requisitionRepository).save(captor.capture());
        assertThat(captor.getValue().getLogoUrl()).isEqualTo("https://logos.example/bnp-fr.png");
    }

    /** New accounts created from a requisition that already carries a logo get it copied over. */
    @Test
    void completeConnection_copiesLogoUrlFromRequisitionOntoNewAccount() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(10L)
            .member(member)
            .requisitionId("code-123")
            .institutionId("BNP_PARIBAS::FR")
            .institutionName("BNP Paribas")
            .logoUrl("https://logos.example/bnp.png")
            .status(RequisitionStatus.CREATED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("session-1");

        AccountData accountData = new AccountData("ext-1", "Compte Courant", "FR76...", "EUR", new BigDecimal("100"));
        when(bankConnector.fetchBalances("session-1")).thenReturn(List.of(accountData));

        when(accountRepository.findByExternalAccountIdAndMemberId("ext-1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ext-1", memberId))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(99L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenReturn(new AccountResponse(99L, "Compte Courant", null, "BNP Paribas", "EUR",
                new BigDecimal("100"), new BigDecimal("100"), null, false, "#6366f1", null,
                "https://logos.example/bnp.png", null, null, null));

        syncService.completeConnection("oauth-code", memberId);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getLogoUrl()).isEqualTo("https://logos.example/bnp.png");
    }

    /** A retry that still sees no accounts must stay retryable instead of becoming a false success. */
    @Test
    void retrySync_emptyAccountListMarksRequisitionFailed() {
        Long memberId = 4L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(40L)
            .member(member)
            .requisitionId("session-40")
            .institutionId("REVOLUT::FR")
            .institutionName("Revolut")
            .status(RequisitionStatus.FAILED)
            .build();

        when(requisitionRepository.findByIdAndMemberId(40L, memberId)).thenReturn(Optional.of(requisition));
        when(bankConnector.fetchBalances("session-40")).thenReturn(List.of());

        List<AccountResponse> responses = syncService.retrySync(40L, memberId);

        assertThat(responses).isEmpty();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.FAILED);
        assertThat(requisition.getLastSyncedAt()).isNull();
        verify(requisitionRepository).save(requisition);
        verify(accountRepository, never()).save(any(Account.class));
    }

    /** A requisition created before logos existed gets backfilled on the next resync. */
    @Test
    void resyncAll_backfillsMissingLogoUrlFromInstitutionSearch() {
        Long memberId = 2L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(20L)
            .member(member)
            .requisitionId("session-2")
            .institutionId("BOURSOBANK::FR")
            .institutionName("BoursoBank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData match = new InstitutionData("BOURSOBANK::FR", "BoursoBank", "BNPAFRPP",
            "https://logos.example/bourso.png", "FR");
        when(bankConnector.searchInstitutions("BoursoBank", "FR")).thenReturn(List.of(match));

        when(bankConnector.fetchBalances("session-2")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/bourso.png");
        assertThat(requisition.getLogoBackfillAttemptedAt()).isNotNull();
    }

    /** Once a backfill attempt has run (hit or miss), it must not be retried on every subsequent sync. */
    @Test
    void resyncAll_doesNotRetryBackfillOnceAttempted() {
        Long memberId = 21L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(21L)
            .member(member)
            .requisitionId("session-21")
            .institutionId("RENAMED_BANK::FR")
            .institutionName("Renamed Bank")
            .logoUrl(null)
            .logoBackfillAttemptedAt(java.time.Instant.now())
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.fetchBalances("session-21")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        verify(bankConnector, org.mockito.Mockito.never()).searchInstitutions(any(), any());
        assertThat(requisition.getLogoUrl()).isNull();
    }

    /** When both an exact id match and a same-named institution from another country are returned, the id wins. */
    @Test
    void resyncAll_backfillPrefersExactIdMatchOverName() {
        Long memberId = 22L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(22L)
            .member(member)
            .requisitionId("session-22")
            .institutionId("REVOLUT::FR")
            .institutionName("Revolut")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData wrongCountryMatch = new InstitutionData("REVOLUT::LT", "Revolut", "REVOLT21",
            "https://logos.example/revolut-lt.png", "LT");
        InstitutionData exactMatch = new InstitutionData("REVOLUT::FR", "Revolut", "REVOLT21",
            "https://logos.example/revolut-fr.png", "FR");
        when(bankConnector.searchInstitutions("Revolut", "FR")).thenReturn(List.of(wrongCountryMatch, exactMatch));
        when(bankConnector.fetchBalances("session-22")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/revolut-fr.png");
    }

    /** A failed institution search during backfill must not break the resync loop. */
    @Test
    void resyncAll_backfillFailureDoesNotBreakSync() {
        Long memberId = 3L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(30L)
            .member(member)
            .requisitionId("session-3")
            .institutionId("UNKNOWN::FR")
            .institutionName("Unknown Bank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.searchInstitutions("Unknown Bank", "FR"))
            .thenThrow(new RuntimeException("provider unavailable"));
        when(bankConnector.fetchBalances("session-3")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        // The failed logo lookup is swallowed inside ensureLogoUrl. The empty
        // balance response is not a successful sync, but an already-LINKED
        // session must not flap to FAILED on a transient provider gap.
        assertThat(requisition.getLogoUrl()).isNull();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.LINKED);
        assertThat(requisition.getLastSyncedAt()).isNull();
        verify(requisitionRepository, never()).save(requisition);
    }
}
