package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {
    @Query("SELECT f.createdAt FROM UserFollow f WHERE f.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    Optional<UserFollow> findByUuid(UUID uuid);

    Optional<UserFollow> findByFollowerAndFollowingAndIsDeletedFalse(User follower, User following);

    // Following list (rows where :follower follows someone) — exclude follows whose
    // TARGET account is soft-deleted or banned.
    @Query("SELECT f FROM UserFollow f WHERE f.follower = :follower AND f.status = :status AND f.isDeleted = false "
            + "AND f.following.isDeleted = false AND f.following.banned = false")
    Page<UserFollow> findByFollowerAndStatusAndIsDeletedFalse(
            @Param("follower") User follower, @Param("status") String status, Pageable pageable);

    // Followers list (rows where someone follows :following) — exclude follows whose
    // FOLLOWER account is soft-deleted or banned.
    @Query("SELECT f FROM UserFollow f WHERE f.following = :following AND f.status = :status AND f.isDeleted = false "
            + "AND f.follower.isDeleted = false AND f.follower.banned = false")
    Page<UserFollow> findByFollowingAndStatusAndIsDeletedFalse(
            @Param("following") User following, @Param("status") String status, Pageable pageable);

    @Query("SELECT COUNT(f) FROM UserFollow f WHERE f.follower = :follower AND f.status = :status AND f.isDeleted = false "
            + "AND f.following.isDeleted = false AND f.following.banned = false")
    long countByFollowerAndStatusAndIsDeletedFalse(@Param("follower") User follower, @Param("status") String status);

    @Query("SELECT COUNT(f) FROM UserFollow f WHERE f.following = :following AND f.status = :status AND f.isDeleted = false "
            + "AND f.follower.isDeleted = false AND f.follower.banned = false")
    long countByFollowingAndStatusAndIsDeletedFalse(@Param("following") User following, @Param("status") String status);

    boolean existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(User follower, User following, String status);

    /**
     * People who follow {@code user} (accepted).
     */
    @Query(
            "SELECT f.follower FROM UserFollow f WHERE f.following = :user AND f.status = 'ACCEPTED' AND f.isDeleted = false "
            + "AND f.follower.isDeleted = false AND f.follower.banned = false")
    List<User> findAcceptedFollowers(
            @Param("user") User user);

    /**
     * People {@code user} follows (accepted).
     */
    @Query(
            "SELECT f.following FROM UserFollow f WHERE f.follower = :user AND f.status = 'ACCEPTED' AND f.isDeleted = false "
            + "AND f.following.isDeleted = false AND f.following.banned = false")
    List<User> findAcceptedFollowing(
            @Param("user") User user);
}
