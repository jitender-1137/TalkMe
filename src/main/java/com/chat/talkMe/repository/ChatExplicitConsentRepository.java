package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatExplicitConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatExplicitConsentRepository extends JpaRepository<ChatExplicitConsent, Long> {
    Optional<ChatExplicitConsent> findByChat(Chat chat);
}
