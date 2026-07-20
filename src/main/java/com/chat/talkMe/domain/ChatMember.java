package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MemberRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "chat_members")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Legacy convenience flag, kept in sync with {@link #role} as
     * {@code role == OWNER || role == ADMIN}. New authorization logic reads
     * {@code role}; this remains for 1:1 back-compat (the 1:1 creator sets it).
     * Always mutate via {@link #setRole(MemberRole)}.
     */
    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private boolean isAdmin = false;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MEMBER'")
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    /** Per-member notification silence (this member's own preference). */
    @Column(name = "is_muted", nullable = false)
    @Builder.Default
    private boolean isMuted = false;

    @Column(name = "is_archived", nullable = false)
    @Builder.Default
    private boolean isArchived = false;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private boolean isPinned = false;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    // ── Group additions ────────────────────────────────────────────────────────

    /** Admin action: temporarily restrict this member from posting (null = not muted-in-group). */
    @Column(name = "muted_until")
    private Instant mutedUntil;

    /** Admin action: banned from the group (rejected at send and re-join). */
    @ColumnDefault("false")
    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private boolean isBanned = false;

    /**
     * When the member left or was removed (WhatsApp-style). A former member keeps
     * read access to history up to this instant but receives no new messages and
     * cannot send. null = active member. Distinct from {@code isDeleted} (which is
     * the 1:1 "delete conversation for me").
     */
    @Column(name = "left_at")
    private Instant leftAt;

    /**
     * Watermark for group unread counting: id of the latest message this member
     * has read. Group unread = messages with id &gt; lastReadMessageId not sent by
     * this member. Avoids O(members × messages) per-message read receipts.
     */
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    /**
     * User explicitly marked this chat as unread (from the chat-list menu). Sticky
     * until they open/read the chat again. When true it forces the unread badge on
     * even if there are no unread messages. Cleared by {@code markRead}.
     */
    @Column(name = "manually_unread", nullable = false)
    @ColumnDefault("false")
    @lombok.Builder.Default
    private boolean manuallyUnread = false;

    /**
     * Set the role and keep the legacy {@code isAdmin} flag in sync. Use this
     * everywhere instead of setting either field directly.
     */
    public void setRole(MemberRole role) {
        this.role = role;
        this.isAdmin = (role == MemberRole.OWNER || role == MemberRole.ADMIN);
    }
}
