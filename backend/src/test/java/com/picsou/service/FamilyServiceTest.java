package com.picsou.service;

import com.picsou.dto.SharingSettingsRequest;
import com.picsou.dto.ReAuthDto;
import com.picsou.model.Account;
import com.picsou.model.AccountDeletionMode;
import com.picsou.model.AccountType;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.SharedResource;
import com.picsou.model.SharingLevel;
import com.picsou.model.UserRole;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AccessKeyRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import com.picsou.repository.UserMfaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock AppUserRepository userRepository;
    @Mock UserMfaRepository userMfaRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock AccountRepository accountRepository;
    @Mock GoalRepository goalRepository;
    @Mock AccessKeyRepository accessKeyRepository;
    @Mock PersistentSessionService persistentSessionService;
    @Mock ReAuthService reAuthService;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks FamilyService familyService;

    private FamilyMember member(String displayName) {
        return FamilyMember.builder()
            .id(3L)
            .displayName(displayName)
            .avatarColor("#6366f1")
            .managed(true)
            .build();
    }

    /** Captures the AppUser persisted by generateActivationToken and returns its username. */
    private String usernameFromActivation(String displayName) {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member(displayName)));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());

        familyService.generateActivationToken(3L);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue().getUsername();
    }

    @Test
    void deriveUsername_slugifiesSimpleName() {
        assertThat(usernameFromActivation("Alice")).isEqualTo("alice");
    }

    @Test
    void deriveUsername_stripsAccentsAndSpaces() {
        assertThat(usernameFromActivation("Jean Dupont")).isEqualTo("jean.dupont");
    }

    @Test
    void deriveUsername_normalizesAccentedChars() {
        assertThat(usernameFromActivation("Élodie")).isEqualTo("elodie");
    }

    @Test
    void deriveUsername_appendsSuffixOnCollision() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("Alice")));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());
        // "alice" is taken, "alice.2" is free
        when(userRepository.existsByUsername("alice")).thenReturn(true);
        when(userRepository.existsByUsername("alice.2")).thenReturn(false);

        familyService.generateActivationToken(3L);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice.2");
    }

    @Test
    void deriveUsername_fallsBackToUserWhenNameHasNoAlphanumerics() {
        assertThat(usernameFromActivation("!!!")).isEqualTo("user");
    }

    // ─── deleteMember ───────────────────────────────────────────────────

    private AppUser appUser(Long id, UserRole role, FamilyMember member) {
        return AppUser.builder()
            .id(id)
            .username("user-" + id)
            .passwordHash("hash-" + id)
            .role(role)
            .activated(true)
            .member(member)
            .build();
    }

    private ReAuthDto reAuth() {
        return new ReAuthDto("current-password", null);
    }

    @Test
    void deleteMember_activatedMember_deletes() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        FamilyMember target = member("Bob");
        AppUser targetUser = appUser(7L, UserRole.MEMBER, target);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.of(targetUser));

        familyService.deleteMember(3L, 1L);

        verify(userRepository).delete(targetUser);
        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_self_throwsForbidden() {
        FamilyMember requesterMember = member("Self");
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cannot delete your own account");

        verify(memberRepository, never()).delete(any());
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void deleteMember_nonAdminRequester_isRejectedAfterReload() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.MEMBER, requesterMember);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of());
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Admin access required");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void deleteMember_nonLastAdmin_deletes() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        FamilyMember target = member("SecondAdmin");
        AppUser targetUser = appUser(7L, UserRole.ADMIN, target);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester, targetUser));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.of(targetUser));

        familyService.deleteMember(3L, 1L);

        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_notFound_throws() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Member not found");
    }

    /**
     * Guards the regression that returned 500: when the target has a login, the
     * {@link AppUser} (loaded for the last-admin guard) must be deleted BEFORE the
     * member. Deleting the member first leaves a managed user pointing at a removed,
     * non-nullable {@code @OneToOne} → Hibernate throws TransientObjectException at flush.
     */
    @Test
    void deleteMember_withLogin_deletesUserBeforeMember() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        FamilyMember target = member("Bob");
        AppUser user = appUser(7L, UserRole.MEMBER, target);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.of(user));

        familyService.deleteMember(3L, 1L);

        InOrder order = inOrder(userRepository, memberRepository);
        order.verify(userRepository).findAllByRoleForUpdate(UserRole.ADMIN);
        order.verify(userRepository).findByIdWithMemberForUpdate(1L);
        order.verify(memberRepository).findByIdForUpdate(3L);
        order.verify(userRepository).findByMemberIdForUpdate(3L);
        order.verify(userRepository).delete(user);
        order.verify(userRepository).flush();
        order.verify(memberRepository).delete(target);
        order.verify(memberRepository).flush();
    }

    @Test
    void deleteMember_managedWithoutLogin_deletesOnlyMember() {
        FamilyMember requesterMember = FamilyMember.builder().id(1L).displayName("Alice").build();
        AppUser requester = appUser(1L, UserRole.ADMIN, requesterMember);
        FamilyMember target = member("Child");
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(requester));
        when(userRepository.findByIdWithMemberForUpdate(1L)).thenReturn(Optional.of(requester));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberIdForUpdate(3L)).thenReturn(Optional.empty());

        familyService.deleteMember(3L, 1L);

        verify(userRepository, never()).delete(any());
        verify(memberRepository).delete(target);
    }

    @Test
    void deleteOwnAccount_lastAdmin_resetsDataButPreservesLoginIdentity() {
        FamilyMember me = member("TheAdmin");
        AppUser user = appUser(7L, UserRole.ADMIN, me);
        user.setTokenVersion(4L);
        user.setAcknowledgedWarning(true);
        user.setActivationToken("pending-reset-link");
        user.setActivationTokenExpires(Instant.parse("2030-01-01T00:00:00Z"));
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(user));
        when(userRepository.findByIdWithMemberForUpdate(7L)).thenReturn(Optional.of(user));
        when(memberRepository.saveAndFlush(any(FamilyMember.class))).thenAnswer(invocation -> {
            FamilyMember replacement = invocation.getArgument(0);
            replacement.setId(99L);
            return replacement;
        });

        ReAuthDto reAuth = reAuth();
        AccountDeletionMode mode = familyService.deleteOwnAccount(7L, reAuth);

        assertThat(mode).isEqualTo(AccountDeletionMode.RESET_LAST_ADMIN);
        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getUsername()).isEqualTo("user-7");
        assertThat(user.getPasswordHash()).isEqualTo("hash-7");
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.isActivated()).isTrue();
        assertThat(user.isAcknowledgedWarning()).isTrue();
        assertThat(user.getMember().getId()).isEqualTo(99L);
        assertThat(user.getMember().getDisplayName()).isEqualTo("TheAdmin");
        assertThat(user.getMember().isManaged()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(5L);
        assertThat(user.getActivationToken()).isNull();
        assertThat(user.getActivationTokenExpires()).isNull();
        InOrder lockOrder = inOrder(userRepository, reAuthService);
        lockOrder.verify(userRepository).findAllByRoleForUpdate(UserRole.ADMIN);
        lockOrder.verify(userRepository).findByIdWithMemberForUpdate(7L);
        verify(reAuthService).verify(user, reAuth);
        verify(userRepository, never()).delete(any());
        verify(userRepository).saveAndFlush(user);
        verify(persistentSessionService).revokeAllForUser(7L);
        verify(accessKeyRepository).deleteAllByCreatedBy(7L);
        verify(memberRepository).delete(me);
        verifyNoInteractions(userMfaRepository);
    }

    @Test
    void deleteOwnAccount_nonLastAdmin_deletesUserBeforeMember() {
        FamilyMember me = member("SecondAdmin");
        AppUser user = appUser(7L, UserRole.ADMIN, me);
        FamilyMember otherMember = FamilyMember.builder().id(8L).displayName("Other").build();
        AppUser otherAdmin = appUser(8L, UserRole.ADMIN, otherMember);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(user, otherAdmin));
        when(userRepository.findByIdWithMemberForUpdate(7L)).thenReturn(Optional.of(user));

        ReAuthDto reAuth = reAuth();
        AccountDeletionMode mode = familyService.deleteOwnAccount(7L, reAuth);

        assertThat(mode).isEqualTo(AccountDeletionMode.DELETE_ACCOUNT);
        InOrder order = inOrder(userRepository, memberRepository, reAuthService);
        order.verify(userRepository).findAllByRoleForUpdate(UserRole.ADMIN);
        order.verify(userRepository).findByIdWithMemberForUpdate(7L);
        order.verify(reAuthService).verify(user, reAuth);
        order.verify(userRepository).delete(user);
        order.verify(userRepository).flush();
        order.verify(memberRepository).delete(me);
        order.verify(memberRepository).flush();
    }

    @Test
    void deleteOwnAccount_memberRole_deletesUnderTheSharedIdentityLockProtocol() {
        FamilyMember me = member("Bob");
        AppUser user = appUser(7L, UserRole.MEMBER, me);
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of());
        when(userRepository.findByIdWithMemberForUpdate(7L)).thenReturn(Optional.of(user));

        ReAuthDto reAuth = reAuth();
        AccountDeletionMode mode = familyService.deleteOwnAccount(7L, reAuth);

        assertThat(mode).isEqualTo(AccountDeletionMode.DELETE_ACCOUNT);
        InOrder order = inOrder(userRepository, memberRepository, reAuthService);
        order.verify(userRepository).findAllByRoleForUpdate(UserRole.ADMIN);
        order.verify(userRepository).findByIdWithMemberForUpdate(7L);
        order.verify(reAuthService).verify(user, reAuth);
        order.verify(userRepository).delete(user);
        order.verify(userRepository).flush();
        order.verify(memberRepository).delete(me);
        order.verify(memberRepository).flush();
    }

    @Test
    void deleteOwnAccount_reAuthFailureRollsBackBeforeMutation() {
        FamilyMember me = member("Bob");
        AppUser user = appUser(7L, UserRole.MEMBER, me);
        ReAuthDto reAuth = reAuth();
        when(userRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of());
        when(userRepository.findByIdWithMemberForUpdate(7L)).thenReturn(Optional.of(user));
        doThrow(new ReAuthService.ReAuthFailedException("invalid password"))
            .when(reAuthService).verify(user, reAuth);

        assertThatThrownBy(() -> familyService.deleteOwnAccount(7L, reAuth))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);

        verify(userRepository, never()).delete(any());
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void previewOwnAccountDeletion_reportsResetForCurrentLastAdmin() {
        FamilyMember me = member("TheAdmin");
        AppUser user = appUser(7L, UserRole.ADMIN, me);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThat(familyService.previewOwnAccountDeletion(7L))
            .isEqualTo(AccountDeletionMode.RESET_LAST_ADMIN);
    }

    @Test
    void previewOwnAccountDeletion_reportsFullDeleteForMemberWithoutCountingAdmins() {
        FamilyMember me = member("Bob");
        AppUser user = appUser(7L, UserRole.MEMBER, me);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThat(familyService.previewOwnAccountDeletion(7L))
            .isEqualTo(AccountDeletionMode.DELETE_ACCOUNT);
        verify(userRepository, never()).countByRole(any());
    }

    // ─── manual sharing ownership ─────────────────────────────────────────

    @Test
    void updateSharingSettings_manualAccount_rejectsForeignResourceId() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "ACCOUNT", SharingLevel.MANUAL, List.of(10L, 20L));
        Account owned = Account.builder()
            .id(10L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        when(accountRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("One or more shared resource IDs not found");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }

    @Test
    void updateSharingSettings_manualGoal_rejectsForeignResourceId() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "GOAL", SharingLevel.MANUAL, List.of(10L, 20L));
        Goal owned = Goal.builder()
            .id(10L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .build();
        when(goalRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("One or more shared resource IDs not found");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }

    @Test
    void updateSharingSettings_manualAccount_savesOnlyValidatedIds() {
        FamilyMember owner = member("Alice");
        SharingSettingsRequest req = new SharingSettingsRequest(
            "ACCOUNT", SharingLevel.MANUAL, List.of(10L, 20L, 10L));
        Account first = Account.builder()
            .id(10L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        Account second = Account.builder()
            .id(20L)
            .name("Savings")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        when(accountRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(first, second));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(3L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));

        familyService.updateSharingSettings(3L, req);

        verify(sharingSettingsRepository).save(any());
        verify(sharedResourceRepository).deleteAllByOwnerMemberIdAndResourceType(3L, "ACCOUNT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SharedResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(sharedResourceRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .extracting(SharedResource::getResourceId)
            .containsExactly(10L, 20L);
    }

    @Test
    void updateSharingSettings_rejectsUnsupportedResourceType() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "TRANSACTION", SharingLevel.MANUAL, List.of(10L));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported resource type");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }
}
