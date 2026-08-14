package com.neo.chat.repository;

import com.neo.chat.domain.ConsentAcceptance;
import com.neo.chat.domain.User;
import com.neo.chat.enums.ConsentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentAcceptanceRepository extends JpaRepository<ConsentAcceptance, Long> {

    List<ConsentAcceptance> findByUser(User user);

    Optional<ConsentAcceptance> findByUserAndConsentType(User user, ConsentType type);
}
