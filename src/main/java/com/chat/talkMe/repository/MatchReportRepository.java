package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT r.createdAt FROM MatchReport r WHERE r.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
