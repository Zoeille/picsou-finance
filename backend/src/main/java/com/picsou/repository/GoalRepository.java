package com.picsou.repository;

import com.picsou.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    @Query("SELECT g FROM Goal g LEFT JOIN FETCH g.accounts ORDER BY g.deadline ASC")
    List<Goal> findAllWithAccounts();
}
