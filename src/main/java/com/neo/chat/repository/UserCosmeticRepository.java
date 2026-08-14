package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserCosmetic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCosmeticRepository extends JpaRepository<UserCosmetic, Long> {

    List<UserCosmetic> findByUser(User user);

    Optional<UserCosmetic> findByUserAndCosmeticCode(User user, String cosmeticCode);

    List<UserCosmetic> findByUserAndEquippedTrue(User user);
}
