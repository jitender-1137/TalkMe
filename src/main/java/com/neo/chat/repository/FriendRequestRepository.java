package com.neo.chat.repository;

import com.neo.chat.domain.FriendRequest;
import com.neo.chat.domain.User;
import com.neo.chat.enums.FriendRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    @Query("SELECT fr.createdAt FROM FriendRequest fr WHERE fr.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    Optional<FriendRequest> findByUuid(UUID uuid);

    /**
     * Latest request for a sender→receiver pair. Uses findFirst (LIMIT 1) so it never
     * throws NonUniqueResultException even if duplicate rows exist (there should be at
     * most one — enforced by the unique constraint on the entity).
     */
    Optional<FriendRequest> findFirstBySenderAndReceiverOrderByIdDesc(User sender, User receiver);

    /**
     * All rows for a pair — used to purge duplicates/leftovers on unfriend.
     */
    List<FriendRequest> findAllBySenderAndReceiver(User sender, User receiver);

    // Newest requests first, so the list shows the most recent at the top. Skip requests
    // whose SENDER account is soft-deleted or banned — a deleted user's pending request
    // must not show in the receiver's list.
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.receiver = :receiver AND fr.status = :status "
            + "AND fr.sender.isDeleted = false AND fr.sender.banned = false "
            + "ORDER BY fr.createdAt DESC")
    List<FriendRequest> findByReceiverAndStatusOrderByCreatedAtDesc(
            @Param("receiver") User receiver, @Param("status") FriendRequestStatus status);

    // Same guard on the outgoing side: skip requests whose RECEIVER is deleted or banned.
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.sender = :sender AND fr.status = :status "
            + "AND fr.receiver.isDeleted = false AND fr.receiver.banned = false "
            + "ORDER BY fr.createdAt DESC")
    List<FriendRequest> findBySenderAndStatusOrderByCreatedAtDesc(
            @Param("sender") User sender, @Param("status") FriendRequestStatus status);

    @Query(
            "SELECT fr.status, COUNT(fr) FROM FriendRequest fr GROUP BY fr.status")
    List<Object[]> countGroupedByStatus();
}
