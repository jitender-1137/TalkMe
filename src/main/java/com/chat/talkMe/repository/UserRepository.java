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
    Optional<User> findByEmail(String email);
    Optional<User> findByUuid(UUID uuid);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

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
}
