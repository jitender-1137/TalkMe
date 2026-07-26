package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserReputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserReputationRepository extends JpaRepository<UserReputation, Long> {

    Optional<UserReputation> findByUser(User user);

    @Query("select r from UserReputation r where r.user.uuid = :uuid")
    Optional<UserReputation> findByUserUuid(@Param("uuid") UUID uuid);

    boolean existsByUser(User user);

    /** Ids of users who already have a reputation snapshot — the aggregation job's work set. */
    @Query("select r.user.id from UserReputation r")
    List<Long> findAllUserIds();
}
