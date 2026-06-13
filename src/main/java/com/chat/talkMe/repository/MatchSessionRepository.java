package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchSession;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchSessionRepository extends JpaRepository<MatchSession, Long> {
    Optional<MatchSession> findByUuid(UUID uuid);

    @Query("SELECT m FROM MatchSession m WHERE (m.host = :user OR m.peer = :user) AND m.isActive = true ORDER BY m.createdAt DESC LIMIT 1")
    Optional<MatchSession> findActiveSessionByUser(User user);
}
