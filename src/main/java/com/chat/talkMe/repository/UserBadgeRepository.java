package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserBadge;
import com.chat.talkMe.enums.BadgeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {

    List<UserBadge> findByUser(User user);

    Optional<UserBadge> findByUserAndBadgeType(User user, BadgeType badgeType);
}
