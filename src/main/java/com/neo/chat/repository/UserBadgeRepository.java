package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserBadge;
import com.neo.chat.enums.BadgeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {

    List<UserBadge> findByUser(User user);

    Optional<UserBadge> findByUserAndBadgeType(User user, BadgeType badgeType);
}
