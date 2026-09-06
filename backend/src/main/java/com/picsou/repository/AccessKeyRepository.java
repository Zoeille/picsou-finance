package com.picsou.repository;

import com.picsou.model.AccessKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccessKeyRepository extends JpaRepository<AccessKey, Long> {

    /** Hot auth path: resolve a presented key by its unique prefix, then constant-time compare the hash. */
    Optional<AccessKey> findByKeyPrefix(String keyPrefix);

    /** Settings list: a member's keys, newest first. */
    List<AccessKey> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /** Member-scoped lookup for revoke (404 if it is not the caller's key). */
    Optional<AccessKey> findByIdAndMemberId(Long id, Long memberId);

    @Modifying
    @Query("DELETE FROM AccessKey a WHERE a.createdBy = :userId")
    int deleteAllByCreatedBy(@Param("userId") Long userId);

    /**
     * Throttled last-used stamp. Bulk update bypasses the entity lifecycle on purpose, so this
     * frequent hot-path write never bumps {@code updated_at} via JPA auditing.
     */
    @Modifying
    @Query("UPDATE AccessKey a SET a.lastUsedAt = :ts WHERE a.id = :id")
    void touchLastUsedAt(@Param("id") Long id, @Param("ts") Instant ts);
}
