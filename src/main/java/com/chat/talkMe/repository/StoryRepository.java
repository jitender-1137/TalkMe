package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT s.createdAt FROM Story s WHERE s.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
    Optional<Story> findByUuid(UUID uuid);

    @Query("SELECT s FROM Story s WHERE s.expiresAt > :now AND s.isDeleted = false ORDER BY s.createdAt DESC")
    List<Story> findActiveStories(Instant now);

    @Query("SELECT s FROM Story s WHERE s.user = :user AND s.expiresAt > :now AND s.isDeleted = false ORDER BY s.createdAt DESC")
    List<Story> findActiveStoriesByUser(User user, Instant now);
}
