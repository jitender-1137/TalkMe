package com.neo.chat.repository;

import com.neo.chat.domain.Friend;
import com.neo.chat.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRepository extends JpaRepository<Friend, Long> {
    Optional<Friend> findByUserAndFriend(User user, User friend);

    // Excludes friends whose ACCOUNT is soft-deleted or banned — a removed/banned user
    // must not surface in anyone's friends list even though the friendship row survives.
    @Query("SELECT f.friend FROM Friend f WHERE f.user = :user AND f.isDeleted = false "
            + "AND f.friend.isDeleted = false AND f.friend.banned = false")
    List<User> findFriendsByUser(User user);

    // ── Admin analytics: friend hierarchy ─────────────────────────────────────
    long countByUserAndIsDeletedFalse(User user);

    /**
     * [userId, friendCount] for every user with ≥1 friend link — for distribution.
     */
    @Query("SELECT f.user.id, COUNT(f) FROM Friend f WHERE f.isDeleted = false GROUP BY f.user.id")
    List<Object[]> countFriendsPerUser();

    /**
     * [User, friendCount] most-connected first — the top of the social graph.
     */
    @Query("SELECT f.user, COUNT(f) FROM Friend f WHERE f.isDeleted = false GROUP BY f.user ORDER BY COUNT(f) DESC")
    List<Object[]> topConnectors(Pageable pageable);
}
