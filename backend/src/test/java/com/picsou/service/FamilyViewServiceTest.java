package com.picsou.service;

import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.SharedResource;
import com.picsou.model.SharingLevel;
import com.picsou.model.SharingSettings;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyViewServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock AccountRepository accountRepository;
    @Mock GoalRepository goalRepository;
    @Mock AccountService accountService;
    @Mock GoalManualContributionRepository contributionRepository;

    @InjectMocks FamilyViewService familyViewService;

    @Test
    void getFamilyDashboard_manualAccounts_usesMemberScopedRepository() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account account = Account.builder()
            .id(10L)
            .name("Shared account")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("123"))
            .build();
        SharedResource resource = SharedResource.builder()
            .ownerMember(owner)
            .resourceType("ACCOUNT")
            .resourceId(10L)
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "ACCOUNT", SharingLevel.MANUAL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.empty());
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(List.of(resource));
        when(accountRepository.findByIdInAndMemberId(List.of(10L), 2L)).thenReturn(List.of(account));
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("123"));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedAccounts()).hasSize(1);
        assertThat(response.totalSharedNetWorth()).isEqualByComparingTo("123");
        verify(accountRepository).findByIdInAndMemberId(List.of(10L), 2L);
        verify(accountRepository, never()).findAllById(any());
    }

    @Test
    void getFamilyDashboard_manualGoals_usesMemberScopedRepository() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Goal goal = Goal.builder()
            .id(20L)
            .name("Shared goal")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .member(owner)
            .build();
        SharedResource resource = SharedResource.builder()
            .ownerMember(owner)
            .resourceType("GOAL")
            .resourceId(20L)
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "GOAL", SharingLevel.MANUAL)));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(List.of(resource));
        when(goalRepository.findByIdInAndMemberId(List.of(20L), 2L)).thenReturn(List.of(goal));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedGoals()).hasSize(1);
        assertThat(response.sharedGoals().getFirst().id()).isEqualTo(20L);
        verify(goalRepository).findByIdInAndMemberId(List.of(20L), 2L);
        verify(goalRepository, never()).findAllById(any());
    }
}
