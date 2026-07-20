package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserPresenceRepository extends JpaRepository<UserPresence, Long> {
    Optional<UserPresence> findByUser(User user);
    long countByStatus(String status);

    // Atomic updates for the high-churn presence paths (connect/disconnect).
    // These avoid optimistic-lock (version) conflicts and lost updates when
    // multiple sessions/listeners touch the same presence row concurrently.

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserPresence p SET p.status = :status, p.lastSeenAt = :lastSeen WHERE p.user.id = :userId")
    void updateStatus(@Param("userId") Long userId, @Param("status") String status, @Param("lastSeen") Instant lastSeen);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserPresence p SET p.status = :status, p.lastSeenAt = :lastSeen, " +
           "p.ghostModeEnabled = false, p.invisibleModeEnabled = false WHERE p.user.id = :userId")
    void resetPresence(@Param("userId") Long userId, @Param("status") String status, @Param("lastSeen") Instant lastSeen);
}
