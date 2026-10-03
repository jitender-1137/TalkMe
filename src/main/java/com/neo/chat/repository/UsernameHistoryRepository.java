package com.neo.chat.repository;

import com.neo.chat.domain.UsernameHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read/write access to the append-only username-change history. Only the SuperAdmin dashboard
 * reads it (per-user, newest first).
 */
@Repository
public interface UsernameHistoryRepository extends JpaRepository<UsernameHistory, Long> {

    /** Every username change for a user, most recent first. */
    List<UsernameHistory> findByUserIdOrderByCreatedAtDesc(Long userId);
}
