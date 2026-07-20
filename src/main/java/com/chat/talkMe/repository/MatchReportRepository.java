package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT r.createdAt FROM MatchReport r WHERE r.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);

    // ── Moderation review portal ──────────────────────────────────────────────
    java.util.Optional<MatchReport> findByUuid(java.util.UUID uuid);
    org.springframework.data.domain.Page<MatchReport> findByStatus(String status, org.springframework.data.domain.Pageable pageable);
    long countByStatus(String status);
    long countByReportedId(Long reportedId);
    long countByReporterId(Long reporterId);
    /** How many times this reporter has reported this same user (duplicate signal). */
    long countByReporterIdAndReportedId(Long reporterId, Long reportedId);
    /** Guard: does this reporter already have an OPEN (pending) report against this user? */
    boolean existsByReporterIdAndReportedIdAndStatus(Long reporterId, Long reportedId, String status);
    /** Report history against a user, newest first — for the review context. */
    org.springframework.data.domain.Page<MatchReport> findByReportedId(Long reportedId, org.springframework.data.domain.Pageable pageable);
}
