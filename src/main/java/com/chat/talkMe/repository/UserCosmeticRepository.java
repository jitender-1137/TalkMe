package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserCosmetic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCosmeticRepository extends JpaRepository<UserCosmetic, Long> {

    List<UserCosmetic> findByUser(User user);

    Optional<UserCosmetic> findByUserAndCosmeticCode(User user, String cosmeticCode);

    List<UserCosmetic> findByUserAndEquippedTrue(User user);
}
