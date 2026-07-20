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
    @org.springframework.data.jpa.repository.Query("SELECT f.createdAt FROM UserFollow f WHERE f.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
    Optional<UserFollow> findByUuid(UUID uuid);
    Optional<UserFollow> findByFollowerAndFollowingAndIsDeletedFalse(User follower, User following);
    
    Page<UserFollow> findByFollowerAndStatusAndIsDeletedFalse(User follower, String status, Pageable pageable);
    Page<UserFollow> findByFollowingAndStatusAndIsDeletedFalse(User following, String status, Pageable pageable);

    long countByFollowerAndStatusAndIsDeletedFalse(User follower, String status);
    long countByFollowingAndStatusAndIsDeletedFalse(User following, String status);
    
    boolean existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(User follower, User following, String status);

    /** People who follow {@code user} (accepted). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT f.follower FROM UserFollow f WHERE f.following = :user AND f.status = 'ACCEPTED' AND f.isDeleted = false")
    java.util.List<User> findAcceptedFollowers(
            @org.springframework.data.repository.query.Param("user") User user);

    /** People {@code user} follows (accepted). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT f.following FROM UserFollow f WHERE f.follower = :user AND f.status = 'ACCEPTED' AND f.isDeleted = false")
    java.util.List<User> findAcceptedFollowing(
            @org.springframework.data.repository.query.Param("user") User user);
}
