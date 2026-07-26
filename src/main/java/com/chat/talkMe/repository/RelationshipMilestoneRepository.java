package com.chat.talkMe.repository;

import com.chat.talkMe.domain.RelationshipMilestone;
import com.chat.talkMe.enums.MilestoneType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationshipMilestoneRepository extends JpaRepository<RelationshipMilestone, Long> {

    /** The full timeline for a normalized pair, oldest milestone first. */
    List<RelationshipMilestone> findByUserAIdAndUserBIdOrderByAchievedAtAsc(Long userAId, Long userBId);

    /** Idempotency guard: has this exact milestone (pair + type + source ref) already been recorded? */
    boolean existsByUserAIdAndUserBIdAndTypeAndRef(Long userAId, Long userBId, MilestoneType type, String ref);
}
