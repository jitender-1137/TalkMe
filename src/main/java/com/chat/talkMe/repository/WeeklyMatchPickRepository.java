package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.WeeklyMatchPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WeeklyMatchPickRepository extends JpaRepository<WeeklyMatchPick, Long> {

    /** The current-week curated picks for a user, best match first. */
    List<WeeklyMatchPick> findByUserAndWeekStartOrderByRankAsc(User user, LocalDate weekStart);

    /** Prune rows for weeks older than the given Monday. */
    @Modifying
    void deleteByWeekStartBefore(LocalDate weekStart);
}
