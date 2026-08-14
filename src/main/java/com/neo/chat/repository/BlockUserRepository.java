package com.neo.chat.repository;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockUserRepository extends JpaRepository<BlockUser, Long> {
    Optional<BlockUser> findByUserAndBlocked(User user, User blocked);

    List<BlockUser> findByUser(User user);

    List<BlockUser> findByBlocked(User blocked);

    boolean existsByUserAndBlocked(User user, User blocked);
}
