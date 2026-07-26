package com.chat.talkMe.repository;

import com.chat.talkMe.domain.DailyCompanion;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.CompanionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyCompanionRepository extends JpaRepository<DailyCompanion, Long> {

    /** The user's pairing for a given day (unique). */
    Optional<DailyCompanion> findByUserAndPairDate(User user, LocalDate pairDate);

    boolean existsByUserAndPairDate(User user, LocalDate pairDate);

    /** All the user's pairings in a given status (e.g. their current ACTIVE companion). */
    List<DailyCompanion> findByUserAndStatus(User user, CompanionStatus status);

    /** Reaper feed: ACTIVE pairings whose 24h window has elapsed. */
    List<DailyCompanion> findByStatusAndExpiresAtBefore(CompanionStatus status, Instant cutoff);

    /**
     * Recent companion ids for a user (to avoid re-pairing the same person day after day).
     * Newest pairings first; caller slices the returned list.
     */
    List<DailyCompanion> findByUserOrderByPairDateDesc(User user);
}
