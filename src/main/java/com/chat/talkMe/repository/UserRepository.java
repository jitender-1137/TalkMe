package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
