package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageReaction;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT r.createdAt FROM MessageReaction r WHERE r.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
    Optional<MessageReaction> findByMessageAndUserAndEmoji(Message message, User user, String emoji);
}
