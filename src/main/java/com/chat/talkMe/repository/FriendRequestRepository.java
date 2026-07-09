package com.chat.talkMe.repository;

import com.chat.talkMe.domain.FriendRequest;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FriendRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    Optional<FriendRequest> findByUuid(UUID uuid);
    Optional<FriendRequest> findBySenderAndReceiver(User sender, User receiver);
    // Newest requests first, so the list shows the most recent at the top.
    List<FriendRequest> findByReceiverAndStatusOrderByCreatedAtDesc(User receiver, FriendRequestStatus status);
    List<FriendRequest> findBySenderAndStatusOrderByCreatedAtDesc(User sender, FriendRequestStatus status);
}
