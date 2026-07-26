package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Echo of a submitted feedback entry returned to its author. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackResponse {
    private String id; // maps uuid
    private int rating;
    private String reason;
    private String comment;
    private String type;
    private String contextRef;
    private String status;
    private String createdAt;
}
