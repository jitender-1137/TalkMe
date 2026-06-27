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
    Optional<Post> findByUuid(UUID uuid);
    Optional<Post> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Post> findByShortCodeIsNull();
    Page<Post> findByUserAndIsDeletedFalse(User user, Pageable pageable);
    Page<Post> findByIsDeletedFalse(Pageable pageable);
    long countByUserAndIsDeletedFalse(User user);
    
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND (p.user = :currentUser OR p.user IN (SELECT f.following FROM UserFollow f WHERE f.follower = :currentUser AND f.status = 'ACCEPTED' AND f.isDeleted = false))")
    Page<Post> findFeedForUser(@Param("currentUser") User currentUser, Pageable pageable);
}
