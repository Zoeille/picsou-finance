package com.picsou.repository;

import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequisitionRepository extends JpaRepository<Requisition, Long> {

    // All queries must be member-scoped (see backend/CLAUDE.md) — the single
    // exception is findByOauthState, documented below.
    List<Requisition> findAllByMemberId(Long memberId);
    Optional<Requisition> findByIdAndMemberId(Long id, Long memberId);
    List<Requisition> findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus status, Long memberId);
    List<Requisition> findByStatusAndMemberIdAndInstitutionIdOrderByCreatedAtDesc(
        RequisitionStatus status, Long memberId, String institutionId);

    /**
     * Deliberately not member-scoped: the random single-use state nonce IS the
     * credential binding the OAuth callback to its requisition (the member is
     * derived from the resolved row).
     */
    Optional<Requisition> findByOauthState(String oauthState);
}
