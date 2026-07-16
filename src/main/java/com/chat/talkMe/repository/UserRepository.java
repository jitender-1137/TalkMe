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

    // ── Case-insensitive lookups ─────────────────────────────────────────────
    // Email is treated case-insensitively (users type it in any case); usernames
    // too, for login. These repair already-stored mixed-case rows without a data
    // migration and prevent duplicate accounts differing only by letter case.
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);

    // ── Admin dashboard counters ─────────────────────────────────────────────
    long countByIsVerifiedTrue();
    long countByIsGuestTrue();
    long countByCreatedAtAfter(java.time.Instant since);
    /** Signup timestamps since a cutoff — bucketed by day in the service for charts. */
    @Query("SELECT u.createdAt FROM User u WHERE u.createdAt >= :since")
    List<java.time.Instant> findSignupTimesSince(@Param("since") java.time.Instant since);
    long countByBannedTrue();
    /** One-time correction: guests must never be verified. Returns rows fixed. */
    @Modifying
    @Query("UPDATE User u SET u.isVerified = false WHERE u.isGuest = true AND u.isVerified = true")
    int unverifyAllGuests();
    /** Users seen since a cutoff — "active" counts for the analytics dashboard. */
    long countByPresenceLastSeenAtAfter(java.time.Instant since);
    /** Accounts soft-deleted and awaiting purge (grace window), newest request first. */
    List<User> findByIsDeletedTrueAndDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtDesc();
    @Query("SELECT u.gender, COUNT(u) FROM User u WHERE u.isGuest = false GROUP BY u.gender")
    List<Object[]> countGroupedByGender();
    @Query("SELECT u.country, COUNT(u) FROM User u WHERE u.isGuest = false AND u.country IS NOT NULL GROUP BY u.country ORDER BY COUNT(u) DESC")
    List<Object[]> countGroupedByCountry();
    // Paginated search over name/username/email for the admin user list.
    org.springframework.data.domain.Page<User>
        findByUsernameContainingIgnoreCaseOrNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String name, String email, org.springframework.data.domain.Pageable pageable);

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
