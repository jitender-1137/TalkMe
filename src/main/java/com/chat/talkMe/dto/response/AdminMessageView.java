package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * A message as seen by an admin — content is DECRYPTED server-side (the admin has
 * full access). Media paths are decrypted too so they resolve.
 */
@Data
@Builder
public class AdminMessageView {
    private String id;            // uuid
    private String chatId;        // uuid
    private String senderId;      // uuid
    private String senderUsername;
    private String senderName;
    private String senderAvatar;  // sender profile image (nullable)
    private String type;          // TEXT / IMAGE / VIDEO / ...
    private String content;       // DECRYPTED
    private String mediaUrl;      // DECRYPTED path (nullable)
    private boolean edited;
    private boolean deleted;
    private String moderationStatus;
    private String status;        // SENT / DELIVERED / READ — WhatsApp-style receipt
    private String createdAt;
}
