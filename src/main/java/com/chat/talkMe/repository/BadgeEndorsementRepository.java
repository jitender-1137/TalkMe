package com.chat.talkMe.repository;

import com.chat.talkMe.domain.BadgeEndorsement;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.BadgeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BadgeEndorsementRepository extends JpaRepository<BadgeEndorsement, Long> {

    boolean existsByEndorserAndRecipientAndBadgeType(User endorser, User recipient, BadgeType badgeType);

    long countByRecipientAndBadgeType(User recipient, BadgeType badgeType);
}
