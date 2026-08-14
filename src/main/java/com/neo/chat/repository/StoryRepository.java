package com.neo.chat.repository;

import com.neo.chat.domain.Story;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Story} entities: lookups, the active-stories feed, and the owner archive.
 */
@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {

    @Query("SELECT s.createdAt FROM Story s WHERE s.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    Optional<Story> findByUuid(UUID uuid);

    @Query("SELECT s FROM Story s WHERE s.expiresAt > :now AND s.isDeleted = false ORDER BY s.createdAt DESC")
    List<Story> findActiveStories(Instant now);

    @Query("SELECT s FROM Story s WHERE s.user = :user AND s.expiresAt > :now AND s.isDeleted = false ORDER BY s.createdAt DESC")
    List<Story> findActiveStoriesByUser(User user, Instant now);

    @Query("SELECT s FROM Story s WHERE s.user = :user AND s.isDeleted = false ORDER BY s.createdAt DESC")
    List<Story> findAllByUser(User user);
}
