package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
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

    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private boolean isAdmin = false;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

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
}
