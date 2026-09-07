package com.picsou.controller;

import com.picsou.dto.FamilyMemberRequest;
import com.picsou.dto.FamilyMemberResponse;
import com.picsou.dto.SharingSettingsRequest;
import com.picsou.dto.SharingSettingsResponse;
import com.picsou.service.FamilyService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/family")
public class FamilyController {

    private final FamilyService familyService;
    private final UserContext userContext;

    public FamilyController(FamilyService familyService, UserContext userContext) {
        this.familyService = familyService;
        this.userContext = userContext;
    }

    // ─── Member management (admin-only) ─────────────────────────────────

    @GetMapping("/members")
    public List<FamilyMemberResponse> listMembers() {
        requireAdmin();
        return familyService.listMembers();
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyMemberResponse createMember(@Valid @RequestBody FamilyMemberRequest req) {
        requireAdmin();
        return familyService.createManagedProfile(req);
    }

    @PutMapping("/members/{id}")
    public FamilyMemberResponse updateMember(
        @PathVariable Long id,
        @RequestBody Map<String, String> body
    ) {
        requireAdmin();
        String displayName = body.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName is required");
        }
        return familyService.updateDisplayName(id, displayName);
    }

    @DeleteMapping("/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(@PathVariable Long id) {
        requireAdmin();
        familyService.deleteMember(id, userContext.currentUser().getId());
    }

    @PostMapping("/members/{id}/activate")
    public ResponseEntity<Map<String, String>> generateActivationLink(@PathVariable Long id) {
        requireAdmin();
        String token = familyService.generateActivationToken(id);
        String link = "/activate/" + token;
        return ResponseEntity.ok(Map.of("activationLink", link));
    }

    @PostMapping("/members/{id}/reset-password")
    public ResponseEntity<Map<String, String>> generateResetPasswordLink(@PathVariable Long id) {
        requireAdmin();
        String token = familyService.resetPasswordToken(id);
        String link = "/activate/" + token;
        return ResponseEntity.ok(Map.of("resetLink", link));
    }

    // ─── Sharing settings (any authenticated user) ──────────────────────

    @GetMapping("/sharing")
    public SharingSettingsResponse getSharingSettings(
        @RequestParam String resourceType
    ) {
        return familyService.getSharingSettings(userContext.currentMemberId(), resourceType);
    }

    @PutMapping("/sharing")
    public ResponseEntity<Void> updateSharingSettings(
        @Valid @RequestBody SharingSettingsRequest req
    ) {
        familyService.updateSharingSettings(userContext.currentMemberId(), req);
        return ResponseEntity.ok().build();
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private void requireAdmin() {
        if (!userContext.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }
}
