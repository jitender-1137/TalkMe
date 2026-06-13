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
}
