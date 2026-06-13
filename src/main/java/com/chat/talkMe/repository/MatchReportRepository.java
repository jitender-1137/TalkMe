package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
}
