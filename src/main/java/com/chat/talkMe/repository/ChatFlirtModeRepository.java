package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatFlirtMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatFlirtModeRepository extends JpaRepository<ChatFlirtMode, Long> {

    /** The single flirt-mode row for a chat (if any). */
    Optional<ChatFlirtMode> findByChat(Chat chat);

    /** The single flirt-mode row for a chat resolved by its uuid (if any). */
    @Query("SELECT f FROM ChatFlirtMode f WHERE f.chat.uuid = :chatUuid")
    Optional<ChatFlirtMode> findByChatUuid(@Param("chatUuid") UUID chatUuid);
}
