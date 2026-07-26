package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ConsentAcceptance;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ConsentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentAcceptanceRepository extends JpaRepository<ConsentAcceptance, Long> {

    List<ConsentAcceptance> findByUser(User user);

    Optional<ConsentAcceptance> findByUserAndConsentType(User user, ConsentType type);
}
