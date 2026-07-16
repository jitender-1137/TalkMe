package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRepository extends JpaRepository<Friend, Long> {
    Optional<Friend> findByUserAndFriend(User user, User friend);

    @Query("SELECT f.friend FROM Friend f WHERE f.user = :user AND f.isDeleted = false")
    List<User> findFriendsByUser(User user);

    // ── Admin analytics: friend hierarchy ─────────────────────────────────────
    long countByUserAndIsDeletedFalse(User user);

    /** [userId, friendCount] for every user with ≥1 friend link — for distribution. */
    @Query("SELECT f.user.id, COUNT(f) FROM Friend f WHERE f.isDeleted = false GROUP BY f.user.id")
    List<Object[]> countFriendsPerUser();

    /** [User, friendCount] most-connected first — the top of the social graph. */
    @Query("SELECT f.user, COUNT(f) FROM Friend f WHERE f.isDeleted = false GROUP BY f.user ORDER BY COUNT(f) DESC")
    List<Object[]> topConnectors(org.springframework.data.domain.Pageable pageable);
}
