package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ChatKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatKeyRepository extends JpaRepository<ChatKey, Long> {
    Optional<ChatKey> findByChatId(Long chatId);
}
