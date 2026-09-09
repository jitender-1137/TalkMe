package com.neo.chat.repository;

import com.neo.chat.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByUser_Id(Long userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    // Bulk JPQL delete (BootUI HIB-QUERY-004): the derived deleteBy… variant loaded every row and
    // removed it one by one. The entity has no cascades/orphanRemoval and no @PreRemove hooks, so a
    // single DELETE statement is equivalent.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushSubscription s WHERE s.endpoint = :endpoint")
    void deleteByEndpoint(@Param("endpoint") String endpoint);

    /**
     * Remove every push subscription for a user (used on the single-device login sweep).
     */
    // Intentionally no clearAutomatically: runs inside the login transaction
    // (AuthServiceImpl.generateLoginResponse), which keeps mutating and saving the managed User afterwards.
    @Modifying
    @Query("DELETE FROM PushSubscription p WHERE p.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
