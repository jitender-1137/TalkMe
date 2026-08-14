package com.neo.chat.repository;

import com.neo.chat.domain.BadgeEndorsement;
import com.neo.chat.domain.User;
import com.neo.chat.enums.BadgeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BadgeEndorsementRepository extends JpaRepository<BadgeEndorsement, Long> {

    boolean existsByEndorserAndRecipientAndBadgeType(User endorser, User recipient, BadgeType badgeType);

    long countByRecipientAndBadgeType(User recipient, BadgeType badgeType);
}
