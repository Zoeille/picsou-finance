package com.picsou.repository;

import com.picsou.model.FamilyMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {
    List<FamilyMember> findAllByOrderByCreatedAtAsc();
    List<FamilyMember> findByManagedTrue();
    List<FamilyMember> findByManagedFalse();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM FamilyMember m WHERE m.id = :id")
    Optional<FamilyMember> findByIdForUpdate(@Param("id") Long id);
}
