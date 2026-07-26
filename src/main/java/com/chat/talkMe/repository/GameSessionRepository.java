package com.chat.talkMe.repository;

import com.chat.talkMe.domain.GameSession;
import com.chat.talkMe.enums.GameState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    /** The most-recent non-ended (LOBBY or IN_PROGRESS) session for a chat, if any.
     *  findFirst...OrderByIdDesc so a stray duplicate never throws IncorrectResultSize. */
    Optional<GameSession> findFirstByChatIdAndStateNotOrderByIdDesc(String chatId, GameState state);

    Optional<GameSession> findByUuid(UUID uuid);

    // ── Social Memory / Relationship Journey (feature #19) — "games played together" ──
    long countByChatId(String chatId);

    @org.springframework.data.jpa.repository.Query("SELECT MIN(g.createdAt) FROM GameSession g WHERE g.chatId = :chatId")
    java.time.Instant findFirstGameAt(@org.springframework.data.repository.query.Param("chatId") String chatId);
}
