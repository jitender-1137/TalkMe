package com.neo.chat.repository;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatExplicitConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatExplicitConsentRepository extends JpaRepository<ChatExplicitConsent, Long> {
    Optional<ChatExplicitConsent> findByChat(Chat chat);
}
