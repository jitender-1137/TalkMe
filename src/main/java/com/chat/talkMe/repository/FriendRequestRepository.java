package com.chat.talkMe.repository;

import com.chat.talkMe.domain.FriendRequest;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FriendRequestStatus;
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

    // Newest requests first, so the list shows the most recent at the top.
    List<FriendRequest> findByReceiverAndStatusOrderByCreatedAtDesc(User receiver, FriendRequestStatus status);

    List<FriendRequest> findBySenderAndStatusOrderByCreatedAtDesc(User sender, FriendRequestStatus status);

    @Query(
            "SELECT fr.status, COUNT(fr) FROM FriendRequest fr GROUP BY fr.status")
    List<Object[]> countGroupedByStatus();
}
