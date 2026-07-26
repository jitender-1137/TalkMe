package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One physical object in the media store, reconciled against the DB. When an object
 * matches a chat attachment it carries that attachment's context ({@code linked=true});
 * when it exists in storage with no DB row it is an {@code orphan} (linked=false and a
 * chat-media category). Powers the admin Attachments gallery's storage-truth view.
 */
@Data
@Builder
public class AdminStorageObjectView {
    private String key;            // object key (path under media-root)
    private String reference;      // <media-root>/<key> — the DB-shaped reference
    private String url;            // serve URL (/api/v1/uploads/media?path=...)
    private String kind;           // image / video / voice / audio / file
    private String category;       // conversations / lobby / strangers / profiles / posts / stories / other
    private long size;
    private String contentType;
    private String lastModified;   // ISO instant (nullable) — object's storage last-modified

    private boolean linked;        // true = has a matching DB attachment row
    private boolean orphan;        // true = chat-media object with no DB row

    // ── Enrichment (present only when linked to a chat attachment) ──────────────
    private String attachmentId;   // uuid
    private String messageId;      // uuid
    private String chatId;         // uuid
    private String chatName;
    private String chatType;

    // Owner / sender (the person who sent the attachment)
    private String senderId;       // uuid
    private String senderUsername;
    private String senderName;
    private String senderAvatar;

    // Receivers — everyone in the chat other than the sender (who the file was shared with)
    private List<SharedUser> receivers;

    // Media info
    private String fileName;       // DECRYPTED display name
    private String mimeType;
    private Double duration;       // seconds — audio/voice/video
    private long fileSize;         // DB-recorded size (may differ from physical `size`)

    private String thumbnailUrl;   // DECRYPTED thumbnail reference (image/video), if any

    // Message context — the message this file was sent in
    private String caption;            // DECRYPTED text sent alongside the media (may be empty)
    private String messageType;        // IMAGE / VIDEO / VOICE / AUDIO / FILE / ...
    private boolean forwarded;         // the message was forwarded
    private boolean edited;            // the message was edited after sending
    private String moderationStatus;   // CLEAN / HELD / BLOCKED …
    private int reactionCount;         // number of reactions on the message
    private Integer selfDestructSeconds; // view-once / timed media (null = normal)
    private boolean selfDestructExpired; // the media has already self-destructed

    // Timestamps
    private String sentAt;         // when the message was sent (message.createdAt)
    private String createdAt;      // attachment row created
    private String updatedAt;      // attachment row updated

    private boolean deleted;       // owning message soft-deleted

    /** A chat participant the attachment was shared with (a receiver). */
    @Data
    @Builder
    public static class SharedUser {
        private String id;         // uuid
        private String username;
        private String name;
        private String avatar;
    }
}
