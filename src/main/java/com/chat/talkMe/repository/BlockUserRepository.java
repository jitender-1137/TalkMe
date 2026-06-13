package com.chat.talkMe.repository;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockUserRepository extends JpaRepository<BlockUser, Long> {
    Optional<BlockUser> findByUserAndBlocked(User user, User blocked);
    List<BlockUser> findByUser(User user);
    boolean existsByUserAndBlocked(User user, User blocked);
}
