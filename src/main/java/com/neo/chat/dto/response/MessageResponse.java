package com.neo.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String chatId; // owning chat uuid (needed by the starred-messages list)
    private String clientId; // echoes the client idempotency key so the sender's own broadcast dedups
    private Long sequenceNumber;
    private String senderId; // maps to sender.uuid
    private String senderName; // sender display name (group bubbles show it)
    private String senderAvatar; // sender profile image (group bubbles show it)
    private boolean starred; // whether the CURRENT user has starred (saved) this message
    private String content;
    private String messageType; // MessageType enum representation
    private String createdAt; // sent time (message creation)
    // Recipient delivery/read instants (ISO-8601), for the sender's "Message info" ladder.
    // Null until the message reaches that state; ghost recipients are excluded (privacy).
    private String deliveredAt; // when it reached the recipient (DELIVERED)
    private String readAt;      // when the recipient opened it (READ)
    private boolean isEdited;
    // Force the JSON key to "isForwarded" (Lombok's is-getter would otherwise emit "forwarded").
    @JsonProperty("isForwarded")
    private boolean isForwarded; // true → render a "Forwarded" label
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

    // Media download permission: false (default) → receiver's viewer hides the
    // Download/Save action; true → sender allowed downloading THIS media.
    private boolean allowDownload;
}
