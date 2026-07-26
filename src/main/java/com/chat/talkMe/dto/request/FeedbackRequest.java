package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for submitting feedback. The service requires at least one of
 * {@code rating}, {@code reason} or {@code comment} to be present (empty
 * submissions are rejected); the "fully blocking" logout/deletion prompts
 * enforce rating + comment client-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequest {

    @Min(value = 0, message = "Rating must be between 0 and 5")
    @Max(value = 5, message = "Rating must be between 0 and 5")
    private int rating;

    @Size(max = 120, message = "Reason is too long")
    private String reason;

    @Size(max = 4000, message = "Comment is too long")
    private String comment;

    /** FeedbackType name: LOGOUT, ACCOUNT_DELETION, LEAVE_GROUP, LEAVE_ROOM, MANUAL, OTHER. */
    private String type;

    @Size(max = 160, message = "Context is too long")
    private String contextRef;

    @Size(max = 32)
    private String platform;
}
