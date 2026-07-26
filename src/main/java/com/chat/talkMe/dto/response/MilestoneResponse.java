package com.chat.talkMe.dto.response;

import com.chat.talkMe.domain.RelationshipMilestone;
import com.chat.talkMe.enums.MilestoneType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One entry in a Relationship Journey timeline (feature #19). The {@code label} is the
 * human-readable copy carried by {@link MilestoneType} so the client renders it directly.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilestoneResponse {

    private MilestoneType type;
    private String label;
    private Instant achievedAt;
    private String detail;

    public static MilestoneResponse from(RelationshipMilestone m) {
        return MilestoneResponse.builder()
                .type(m.getType())
                .label(m.getType() != null ? m.getType().getLabel() : null)
                .achievedAt(m.getAchievedAt())
                .detail(m.getDetail())
                .build();
    }
}
