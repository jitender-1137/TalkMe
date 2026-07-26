package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * The Relationship Journey between the caller and one other user (feature #19,
 * RELATIONSHIP_JOURNEY): the other user's UUID plus the ordered milestone timeline.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipJourneyResponse {

    private String otherUserUuid;
    private List<MilestoneResponse> milestones;
    /** Aggregate friendship stats (messages/photos/games/days). Null if not computable. */
    private RelationshipStatsResponse stats;
}
