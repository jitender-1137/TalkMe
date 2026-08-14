package com.neo.chat.repository;

import com.neo.chat.domain.Message;
import com.neo.chat.domain.MessageReaction;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {
    @Query("SELECT r.createdAt FROM MessageReaction r WHERE r.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    Optional<MessageReaction> findByMessageAndUserAndEmoji(Message message, User user, String emoji);
}
