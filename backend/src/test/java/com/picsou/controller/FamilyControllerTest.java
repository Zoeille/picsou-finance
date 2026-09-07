package com.picsou.controller;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.service.FamilyService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyControllerTest {

    @Mock FamilyService familyService;
    @Mock UserContext userContext;
    @InjectMocks FamilyController controller;

    @Test
    void deleteMember_usesCanonicalUserIdRatherThanActiveMemberOverride() {
        AppUser admin = AppUser.builder()
            .id(42L)
            .role(UserRole.ADMIN)
            .member(FamilyMember.builder().id(9L).displayName("Admin").build())
            .build();
        when(userContext.isAdmin()).thenReturn(true);
        when(userContext.currentUser()).thenReturn(admin);

        controller.deleteMember(7L);

        verify(familyService).deleteMember(7L, 42L);
        verify(userContext, never()).currentMemberId();
    }
}
