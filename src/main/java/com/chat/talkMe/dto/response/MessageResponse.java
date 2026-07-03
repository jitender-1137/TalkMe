package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private String id; // maps to uuid
    private String clientId; // echoes the client idempotency key so the sender's own broadcast dedups
    private Long sequenceNumber;
    private String senderId; // maps to sender.uuid
    private String content;
    private String messageType; // MessageType enum representation
    private String createdAt;
    private boolean isEdited;
    private boolean isDeleted; // true = deleted for everyone (tombstone)
    private String moderationStatus; // CLEAN | BLOCKED_PENDING_CONSENT | RELEASED
    private List<MessageReactionResponse> reactions;
    private List<MessageAttachmentResponse> attachments;
    private String status; // SENT, DELIVERED, READ
    private ParentMessageResponse parentMessage;

    // Self-destruct / view-once media: null = normal; 0 = view-once; 5/10/30 = timed.
    private Integer selfDestructSeconds;
    private String selfDestructArmedAt; // ISO instant the receiver first opened it (timer start), else null
    private boolean selfDestructExpired; // true → media destroyed; render the greyed "expired" note
}
