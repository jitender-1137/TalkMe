package com.chat.talkMe.repository;

import com.chat.talkMe.domain.PushSubscription;
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

    void deleteByEndpoint(String endpoint);

    /** Remove every push subscription for a user (used on the single-device login sweep). */
    @Modifying
    @Query("DELETE FROM PushSubscription p WHERE p.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
