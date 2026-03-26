package com.picsou.repository;

import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequisitionRepository extends JpaRepository<Requisition, Long> {
    Optional<Requisition> findByRequisitionId(String requisitionId);
    List<Requisition> findByStatusOrderByCreatedAtDesc(RequisitionStatus status);
    List<Requisition> findAllByOrderByCreatedAtDesc();
}
