package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    // Batch lookup for fan-out (avoids N+1 when notifying all chat recipients).
    List<User> findByUsernameIn(java.util.Collection<String> usernames);
    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleId(String googleId);
    Optional<User> findByUuid(UUID uuid);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /** All AI bot accounts (used by the presence heartbeat that keeps them "online"). */
    @Query("SELECT u FROM User u WHERE u.isBot = true AND u.isDeleted = false")
    List<User> findAllBots();

    @Query("SELECT u FROM User u JOIN UserPresence up ON up.user = u " +
           "WHERE up.status = 'ONLINE' " +
           "AND up.invisibleModeEnabled = false " +
           "AND up.ghostModeEnabled = false " +
           "AND u.id <> :currentUserId " +
           "AND u.isDeleted = false")
    List<User> findAllOnlineUsersExcludeSelf(@Param("currentUserId") Long currentUserId);

    @Query("SELECT u FROM User u WHERE u.username IN :usernames AND (:currentUserId IS NULL OR u.id <> :currentUserId) AND u.isDeleted = false")
    List<User> findAllByUsernameInExcludeSelf(@Param("usernames") java.util.Set<String> usernames, @Param("currentUserId") Long currentUserId);

    // ── Unread badge counter — atomic updates avoid optimistic-lock conflicts
    //    and lost updates from concurrent writers (presence, multiple messages).

    @Modifying
    @Query("UPDATE User u SET u.totalUnreadCount = u.totalUnreadCount + 1 WHERE u.id = :id")
    void incrementTotalUnreadCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE User u SET u.totalUnreadCount = :count WHERE u.id = :id")
    void setTotalUnreadCount(@Param("id") Long id, @Param("count") int count);

    @Query("SELECT u.totalUnreadCount FROM User u WHERE u.id = :id")
    Integer getTotalUnreadCount(@Param("id") Long id);

    /** Soft-deleted accounts whose recovery window has elapsed — due for permanent purge. */
    @Query("SELECT u FROM User u WHERE u.isDeleted = true AND u.deletionRequestedAt IS NOT NULL AND u.deletionRequestedAt < :cutoff")
    List<User> findAccountsDueForPurge(@Param("cutoff") java.time.Instant cutoff);
}
