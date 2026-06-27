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
        @Index(name = "idx_messages_sender_id", columnList = "sender_id")
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

    @Column(name = "is_edited", nullable = false)
    @Builder.Default
    private boolean isEdited = false;

    @Column(name = "is_blocked", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean isBlocked = false;

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
}
