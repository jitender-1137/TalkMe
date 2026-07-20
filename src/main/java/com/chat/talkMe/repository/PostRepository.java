package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Post;
import com.chat.talkMe.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT p.createdAt FROM Post p WHERE p.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
    Optional<Post> findByUuid(UUID uuid);
    Optional<Post> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Post> findByShortCodeIsNull();
    Page<Post> findByUserAndIsDeletedFalse(User user, Pageable pageable);
    Page<Post> findByIsDeletedFalse(Pageable pageable);
    long countByUserAndIsDeletedFalse(User user);
    
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND (p.user = :currentUser OR p.user IN (SELECT f.following FROM UserFollow f WHERE f.follower = :currentUser AND f.status = 'ACCEPTED' AND f.isDeleted = false))")
    Page<Post> findFeedForUser(@Param("currentUser") User currentUser, Pageable pageable);

    /**
     * The public/explore feed as seen by {@code viewer}: EVERYONE posts always show;
     * FRIENDS (followers &amp; following) posts only when the viewer is the author or has
     * an ACCEPTED follow relationship in either direction. Keeps followers-only posts
     * out of the global explore grid for people who shouldn't see them.
     */
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND (" +
           "p.audience = com.chat.talkMe.enums.PostAudience.EVERYONE OR p.user = :viewer OR " +
           "EXISTS (SELECT 1 FROM UserFollow f WHERE f.status = 'ACCEPTED' AND f.isDeleted = false AND " +
           "((f.follower = :viewer AND f.following = p.user) OR (f.follower = p.user AND f.following = :viewer)))" +
           ")")
    Page<Post> findVisibleFeedFor(@Param("viewer") User viewer, Pageable pageable);

    /**
     * A user's profile feed as seen by {@code viewer}: EVERYONE posts always show;
     * FRIENDS posts only when the viewer is the author or an accepted friend (an
     * ACCEPTED UserFollow in either direction).
     */
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND p.user = :target AND (" +
           "p.audience = com.chat.talkMe.enums.PostAudience.EVERYONE OR :viewer = :target OR " +
           "EXISTS (SELECT 1 FROM UserFollow f WHERE f.status = 'ACCEPTED' AND f.isDeleted = false AND " +
           "((f.follower = :viewer AND f.following = :target) OR (f.follower = :target AND f.following = :viewer)))" +
           ")")
    Page<Post> findProfileFeedFor(@Param("target") User target, @Param("viewer") User viewer, Pageable pageable);
}
