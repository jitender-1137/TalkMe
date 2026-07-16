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
    @org.springframework.data.jpa.repository.Query("SELECT fr.createdAt FROM FriendRequest fr WHERE fr.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
    Optional<FriendRequest> findByUuid(UUID uuid);
    Optional<FriendRequest> findBySenderAndReceiver(User sender, User receiver);
    // Newest requests first, so the list shows the most recent at the top.
    List<FriendRequest> findByReceiverAndStatusOrderByCreatedAtDesc(User receiver, FriendRequestStatus status);
    List<FriendRequest> findBySenderAndStatusOrderByCreatedAtDesc(User sender, FriendRequestStatus status);

    @org.springframework.data.jpa.repository.Query(
        "SELECT fr.status, COUNT(fr) FROM FriendRequest fr GROUP BY fr.status")
    List<Object[]> countGroupedByStatus();
}
