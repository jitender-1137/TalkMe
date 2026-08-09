package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    @Query("SELECT r.createdAt FROM MatchReport r WHERE r.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    // ── Moderation review portal ──────────────────────────────────────────────
    Optional<MatchReport> findByUuid(UUID uuid);

    Page<MatchReport> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    long countByReportedId(Long reportedId);

    long countByReporterId(Long reporterId);

    /**
     * How many times this reporter has reported this same user (duplicate signal).
     */
    long countByReporterIdAndReportedId(Long reporterId, Long reportedId);

    /**
     * Guard: does this reporter already have an OPEN (pending) report against this user?
     */
    boolean existsByReporterIdAndReportedIdAndStatus(Long reporterId, Long reportedId, String status);

    /**
     * Report history against a user, newest first — for the review context.
     */
    Page<MatchReport> findByReportedId(Long reportedId, Pageable pageable);
}
