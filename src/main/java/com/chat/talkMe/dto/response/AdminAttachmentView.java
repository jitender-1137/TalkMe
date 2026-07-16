package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * An attachment as seen by an admin: who sent it, in which chat, and who it was
 * shared with. URLs are DECRYPTED so they resolve. Everything carries the ids
 * needed to jump (single click) to the message / chat / user.
 */
@Data
@Builder
public class AdminAttachmentView {
    private String id;            // attachment uuid
    private String messageId;     // message uuid
    private String chatId;        // chat uuid
    private String chatName;
    private String chatType;      // PRIVATE / GROUP / ...

    private String senderId;      // uuid
    private String senderUsername;
    private String senderName;
    private String senderAvatar;

    /** Chat members other than the sender — who received/can see the file. */
    private List<SharedUser> sharedWith;

    private String type;          // message type (IMAGE / VIDEO / AUDIO / DOCUMENT / ...)
    private String fileName;      // DECRYPTED
    private String fileUrl;       // DECRYPTED path
    private String thumbnailUrl;  // DECRYPTED path (nullable)
    private String mimeType;
    private long fileSize;
    private String createdAt;

    @Data
    @Builder
    public static class SharedUser {
        private String id;        // uuid
        private String username;
        private String name;
        private String avatar;
    }
}
