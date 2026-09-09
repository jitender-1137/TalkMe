package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.WeeklyMatchPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WeeklyMatchPickRepository extends JpaRepository<WeeklyMatchPick, Long> {

    /**
     * The current-week curated picks for a user, best match first.
     */
    List<WeeklyMatchPick> findByUserAndWeekStartOrderByRankAsc(User user, LocalDate weekStart);

    /**
     * Prune rows for weeks older than the given Monday.
     */
    // Bulk JPQL delete (BootUI HIB-QUERY-004): the derived deleteBy… variant loaded every row and
    // removed it one by one. The entity has no cascades/orphanRemoval and no @PreRemove hooks, so a
    // single DELETE statement is equivalent.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WeeklyMatchPick p WHERE p.weekStart < :weekStart")
    void deleteByWeekStartBefore(@Param("weekStart") LocalDate weekStart);
}
