package com.neo.chat.repository;

import com.neo.chat.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    // Batch lookup for fan-out (avoids N+1 when notifying all chat recipients).
    List<User> findByUsernameIn(Collection<String> usernames);

    Optional<User> findByGoogleId(String googleId);

    Optional<User> findByUuid(UUID uuid);

    /**
     * Loads a user by UUID with the LAZY {@code personality} trait map fetch-joined, so compatibility
     * scoring can read it on a detached instance (open-in-view is disabled; see application.yml).
     *
     * @param uuid the user's public UUID
     * @return the user with {@code personality} initialised, if present
     */
    @Query("select u from User u left join fetch u.personality where u.uuid = :uuid")
    Optional<User> findByUuidWithPersonality(@Param("uuid") UUID uuid);

    /**
     * Loads a user by id with the LAZY {@code personality} trait map fetch-joined. Used to re-load
     * the (detached) authenticated principal before compatibility scoring.
     *
     * @param id the user's database id
     * @return the user with {@code personality} initialised, if present
     */
    @Query("select u from User u left join fetch u.personality where u.id = :id")
    Optional<User> findByIdWithPersonality(@Param("id") Long id);

    boolean existsByUsername(String username);

    // ── Case-insensitive lookups ─────────────────────────────────────────────
    // Email is treated case-insensitively (users type it in any case); usernames
    // too, for login. These repair already-stored mixed-case rows without a data
    // migration and prevent duplicate accounts differing only by letter case.
    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    // ── Referrals (attribution only — no reward payout) ──────────────────────
    // Count excludes soft-deleted joiners so the headline matches the listed rows.
    long countByReferredByAndIsDeletedFalse(User referredBy);

    List<User> findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(
            User referredBy, Pageable pageable);

    // ── Admin dashboard counters ─────────────────────────────────────────────
    long countByIsVerifiedTrue();

    long countByIsGuestTrue();

    /**
     * Recently-joined real accounts (Night Owl Lobby "recently joined", feature #2).
     */
    List<User> findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
            Pageable pageable);

    long countByCreatedAtAfter(Instant since);

    /**
     * Signup timestamps since a cutoff — bucketed by day in the service for charts.
     */
    @Query("SELECT u.createdAt FROM User u WHERE u.createdAt >= :since")
    List<Instant> findSignupTimesSince(@Param("since") Instant since);

    long countByBannedTrue();

    /**
     * Soft-deleted (is_deleted = true) vs active account counts for the dashboard.
     */
    long countByIsDeletedTrue();

    long countByIsDeletedFalse();

    /**
     * One-time correction: guests must never be verified. Returns rows fixed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.isVerified = false WHERE u.isGuest = true AND u.isVerified = true")
    int unverifyAllGuests();

    /**
     * Users seen since a cutoff — "active" counts for the analytics dashboard.
     */
    long countByPresenceLastSeenAtAfter(Instant since);

    /**
     * Accounts soft-deleted and awaiting purge (grace window), newest request first.
     */
    List<User> findByIsDeletedTrueAndDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtDesc();

    @Query("SELECT u.gender, COUNT(u) FROM User u WHERE u.isGuest = false GROUP BY u.gender")
    List<Object[]> countGroupedByGender();

    @Query("SELECT u.country, COUNT(u) FROM User u WHERE u.isGuest = false AND u.country IS NOT NULL GROUP BY u.country ORDER BY COUNT(u) DESC")
    List<Object[]> countGroupedByCountry();

    @Query("SELECT u FROM User u JOIN UserPresence up ON up.user = u LEFT JOIN FETCH u.presence " +
            "WHERE up.status = 'ONLINE' " +
            "AND up.invisibleModeEnabled = false " +
            "AND up.ghostModeEnabled = false " +
            "AND u.id <> :currentUserId " +
            "AND u.isDeleted = false AND u.banned = false")
    List<User> findAllOnlineUsersExcludeSelf(@Param("currentUserId") Long currentUserId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.presence WHERE u.username IN :usernames AND (:currentUserId IS NULL OR u.id <> :currentUserId) AND u.isDeleted = false AND u.banned = false")
    List<User> findAllByUsernameInExcludeSelf(@Param("usernames") Set<String> usernames, @Param("currentUserId") Long currentUserId);

    // ── Unread badge counter — atomic updates avoid optimistic-lock conflicts
    //    and lost updates from concurrent writers (presence, multiple messages).

    // Intentionally no clearAutomatically: NotificationDispatchServiceImpl.onNewMessage() keeps
    // reading the managed recipient User (and MessageBroadcaster reuses the loaded recipients) afterwards.
    @Modifying
    @Query("UPDATE User u SET u.totalUnreadCount = u.totalUnreadCount + 1 WHERE u.id = :id")
    void incrementTotalUnreadCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.totalUnreadCount = :count WHERE u.id = :id")
    void setTotalUnreadCount(@Param("id") Long id, @Param("count") int count);

    @Query("SELECT u.totalUnreadCount FROM User u WHERE u.id = :id")
    Integer getTotalUnreadCount(@Param("id") Long id);

    /**
     * Soft-deleted accounts whose recovery window has elapsed — due for permanent purge.
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.presence WHERE u.isDeleted = true AND u.deletionRequestedAt IS NOT NULL AND u.deletionRequestedAt < :cutoff")
    List<User> findAccountsDueForPurge(@Param("cutoff") Instant cutoff);
}
