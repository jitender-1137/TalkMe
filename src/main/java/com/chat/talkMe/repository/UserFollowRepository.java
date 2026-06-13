package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {
    Optional<UserFollow> findByUuid(UUID uuid);
    Optional<UserFollow> findByFollowerAndFollowingAndIsDeletedFalse(User follower, User following);
    
    Page<UserFollow> findByFollowerAndStatusAndIsDeletedFalse(User follower, String status, Pageable pageable);
    Page<UserFollow> findByFollowingAndStatusAndIsDeletedFalse(User following, String status, Pageable pageable);

    long countByFollowerAndStatusAndIsDeletedFalse(User follower, String status);
    long countByFollowingAndStatusAndIsDeletedFalse(User following, String status);
    
    boolean existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(User follower, User following, String status);
}
