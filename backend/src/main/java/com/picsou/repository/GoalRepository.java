package com.picsou.repository;

import com.picsou.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    @Query("SELECT g FROM Goal g LEFT JOIN FETCH g.accounts ORDER BY g.deadline ASC")
    List<Goal> findAllWithAccounts();

    List<Goal> findAllByMemberIdOrderByCreatedAtAsc(Long memberId);

    Optional<Goal> findByIdAndMemberId(Long id, Long memberId);

    /**
     * Member-scoped bulk lookup: resolves caller-supplied goal ids while filtering out
     * goals belonging to other members, preventing IDOR when ids come from the client.
     */
    List<Goal> findByIdInAndMemberId(List<Long> ids, Long memberId);
}
