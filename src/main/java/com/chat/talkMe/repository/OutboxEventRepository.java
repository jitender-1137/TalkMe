package com.chat.talkMe.repository;

import com.chat.talkMe.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Ids of rows still pending past the grace cutoff, oldest first. Read-only and
     * lock-free — the per-row claim happens in {@link #lockPendingById(Long)} so the
     * scan never blocks the fast path.
     */
    @Query("SELECT o.id FROM OutboxEvent o " +
           "WHERE o.status = 'PENDING' AND o.createdAt < :cutoff ORDER BY o.createdAt ASC")
    List<Long> findPendingIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Claims a single pending row for this instance. {@code FOR UPDATE SKIP LOCKED}
     * makes the poller multi-instance safe: a row already being processed by another
     * node is skipped (returns empty) rather than waited on.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE id = :id AND status = 'PENDING' " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboxEvent> lockPendingById(@Param("id") Long id);

    /** Marks the row for an event delivered. Idempotent — safe to call repeatedly. */
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'PUBLISHED', o.publishedAt = :now " +
           "WHERE o.eventKey = :eventKey AND o.status = 'PENDING'")
    int markPublished(@Param("eventKey") String eventKey, @Param("now") Instant now);

    /** Housekeeping: drop delivered rows older than the cutoff so the table stays small. */
    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
