package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MessageType;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "messages", uniqueConstraints = {
        // Idempotency key: a client-generated id makes message sends idempotent.
        // A retried POST (same chat+sender+client_id) cannot create a duplicate.
        // Postgres treats NULL as distinct, so legacy rows (null client_id) never
        // collide — only client-supplied ids are deduped.
        @UniqueConstraint(name = "uk_message_chat_sender_client", columnNames = {"chat_id", "sender_id", "client_id"})
}, indexes = {
        // Primary chat-history access path: every pagination/sync/cursor query
        // filters by chat and orders/ranges by the monotonic id. A composite
        // (chat_id, id) turns those from seq scans into index range scans.
        @Index(name = "idx_messages_chat_id_id", columnList = "chat_id, id"),
        // Covers clearedAt range filters and findFirst...OrderByCreatedAtDesc.
        @Index(name = "idx_messages_chat_id_created_at", columnList = "chat_id, created_at"),
        // Sender-scoped lookups / unread fan-out joins (Postgres does not
        // auto-index FK referencing columns).
        @Index(name = "idx_messages_sender_id", columnList = "sender_id"),
        // Release-on-consent query: find held messages per chat.
        @Index(name = "idx_messages_chat_moderation", columnList = "chat_id, moderation_status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    // Client-generated idempotency key (UUID). Nullable for legacy/server-origin
    // messages. Deduped via uk_message_chat_sender_client.
    @Column(name = "client_id", length = 64)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    @Builder.Default
    private MessageType messageType = MessageType.TEXT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_message_id")
    private Message parentMessage;

    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "is_forwarded", nullable = false)
    @Builder.Default
    private boolean isForwarded = false;

    @Column(name = "is_edited", nullable = false)
    @Builder.Default
    private boolean isEdited = false;

    @Column(name = "is_blocked", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean isBlocked = false;

    // Content-moderation state (distinct from is_blocked which is user-to-user blocking).
    // @ColumnDefault gives a real DB DEFAULT so ddl-auto can add this NOT NULL column
    // to an existing, non-empty table (Postgres backfills existing rows). Unlike
    // columnDefinition, it isn't injected into a "SET DATA TYPE" clause on later runs,
    // so it won't generate invalid ALTER statements once the column exists.
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.ColumnDefault("'CLEAN'")
    @Column(name = "moderation_status", nullable = false, length = 32)
    @Builder.Default
    private com.chat.talkMe.enums.ModerationStatus moderationStatus = com.chat.talkMe.enums.ModerationStatus.CLEAN;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MessageReaction> reactions = new ArrayList<>();

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MessageReadReceipt> readReceipts = new ArrayList<>();

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MessageAttachment> attachments = new ArrayList<>();

    /**
     * "Delete for me" tracking: user ids that have hidden this message only on
     * their own side. The global {@code isDeleted} flag (from BaseEntity) means
     * "deleted for everyone" (sender removed it → tombstone for all). This set is
     * the per-user variant: a recipient deleting a message they didn't send just
     * adds their id here, so it's filtered out of their queries but stays for the
     * sender.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "message_deleted_for",
            joinColumns = @JoinColumn(name = "message_id"),
            indexes = @Index(name = "idx_message_deleted_for_user", columnList = "user_id"))
    @Column(name = "user_id", nullable = false)
    @Builder.Default
    private Set<Long> deletedForUserIds = new HashSet<>();

    // ── Telegram-style self-destruct / view-once media (1:1 only) ──────────────
    // null  = normal media (no self-destruct)
    // 0     = view-once (destroyed shortly after the receiver opens/closes it)
    // 5/10/30 = destroyed N seconds after the RECEIVER opens it
    @Column(name = "self_destruct_seconds")
    private Integer selfDestructSeconds;

    // Set the moment the RECEIVER first opens (reveals) the media — this is the ONLY
    // thing that arms the timer. null until then. Deadline = armedAt + (seconds>0 ?
    // seconds : VIEW_ONCE_GRACE).
    @Column(name = "self_destruct_armed_at")
    private java.time.Instant selfDestructArmedAt;

    // Once the media has been destroyed (file + attachment removed) the message row is
    // kept as a greyed "expired" note. @ColumnDefault lets ddl-auto add the NOT-NULL
    // column to the existing table.
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "self_destruct_expired", nullable = false)
    @Builder.Default
    private boolean selfDestructExpired = false;

    // ── Group message pinning ──────────────────────────────────────────────────
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "pinned", nullable = false)
    @Builder.Default
    private boolean pinned = false;

    @Column(name = "pinned_at")
    private java.time.Instant pinnedAt;

    @Column(name = "pinned_by")
    private Long pinnedBy;

    /**
     * @-mention target user ids (groups). Stored as a side collection; drives
     * targeted notification even when the group is muted.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "message_mentions",
            joinColumns = @JoinColumn(name = "message_id"),
            indexes = @Index(name = "idx_message_mentions_user", columnList = "mentioned_user_id"))
    @Column(name = "mentioned_user_id", nullable = false)
    @Builder.Default
    private Set<Long> mentionedUserIds = new HashSet<>();
}
