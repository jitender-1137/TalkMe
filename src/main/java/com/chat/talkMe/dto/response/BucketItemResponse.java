package com.chat.talkMe.dto.response;

import com.chat.talkMe.domain.BucketListItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Client-facing view of one entry in a shared bucket list (feature #18). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BucketItemResponse {

    /** The item's uuid (as String); used by the toggle/remove routes. */
    private String id;
    private String text;
    private boolean completed;
    private Long completedByUserId;
    private Instant completedAt;
    private Long createdByUserId;
    private int orderIndex;

    public static BucketItemResponse from(BucketListItem item) {
        return BucketItemResponse.builder()
                .id(item.getUuid().toString())
                .text(item.getText())
                .completed(item.isCompleted())
                .completedByUserId(item.getCompletedByUserId())
                .completedAt(item.getCompletedAt())
                .createdByUserId(item.getCreatedByUserId())
                .orderIndex(item.getOrderIndex())
                .build();
    }
}
