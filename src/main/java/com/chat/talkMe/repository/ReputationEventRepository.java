package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ReputationEvent;
import com.chat.talkMe.enums.ReputationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ReputationEventRepository extends JpaRepository<ReputationEvent, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    long countByUserIdAndTypeAndDayBucket(Long userId, ReputationEventType type, LocalDate dayBucket);

    @Query("select coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.userId = :userId and e.type = :type and e.dayBucket = :day")
    int sumAwardedForType(@Param("userId") Long userId,
                          @Param("type") ReputationEventType type,
                          @Param("day") LocalDate day);

    @Query("select coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.userId = :userId and e.dayBucket = :day")
    int sumAwardedForDay(@Param("userId") Long userId, @Param("day") LocalDate day);

    // --- Aggregation / snapshot support (features #30/#31) --------------------------------

    /** Sum of counted, awarded points for a user from ledger rows newer than a cursor id. */
    @Query("select coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.userId = :userId and e.counted = true and e.id > :sinceId")
    long sumAwardedForUserSinceId(@Param("userId") Long userId, @Param("sinceId") long sinceId);

    /** Bounded delta sum ({@code sinceId < id <= maxId}) — prevents double-counting a row
     *  committed between reading maxId and summing (the cursor only advances to maxId). */
    @Query("select coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.userId = :userId and e.counted = true and e.id > :sinceId and e.id <= :maxId")
    long sumAwardedForUserBetweenIds(@Param("userId") Long userId,
                                     @Param("sinceId") long sinceId, @Param("maxId") long maxId);

    /** Highest ledger id for a user (0 if none) — used only for the cosmetic contributor breakdown. */
    @Query("select coalesce(max(e.id), 0) from ReputationEvent e where e.userId = :userId")
    long findMaxIdForUser(@Param("userId") Long userId);

    /**
     * Ids of a user's ledger rows not yet folded into their snapshot. Recompute sums exactly
     * these and then marks them applied, so no row is ever double-counted or skipped — immune to
     * the sequence-id commit-reordering that a max-id cursor suffers from.
     */
    @Query("select e.id from ReputationEvent e where e.userId = :userId and e.snapshotApplied = false")
    List<Long> findUnappliedIds(@Param("userId") Long userId);

    /** Sum of counted, awarded points across the given ledger rows (0 if none/empty). */
    @Query("select coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.id in :ids and e.counted = true")
    long sumAwardedByIds(@Param("ids") Collection<Long> ids);

    /** Flag the given ledger rows as folded into the owner's snapshot. */
    @Modifying
    @Query("update ReputationEvent e set e.snapshotApplied = true where e.id in :ids")
    void markSnapshotApplied(@Param("ids") Collection<Long> ids);

    /**
     * Per-type counted point sums for a user, over all newly-applied ledger rows. Each row is
     * {@code [ReputationEventType type, long total]}; drives the contributor breakdown (labels only).
     */
    @Query("select e.type, coalesce(sum(e.awardedWeight), 0) from ReputationEvent e " +
            "where e.userId = :userId and e.counted = true and e.id <= :maxId " +
            "group by e.type")
    List<Object[]> sumAwardedPerTypeUpToId(@Param("userId") Long userId, @Param("maxId") long maxId);

    /** Distinct user ids that have any ledger activity — the aggregation job's candidate set. */
    @Query("select distinct e.userId from ReputationEvent e")
    List<Long> findDistinctUserIds();
}
