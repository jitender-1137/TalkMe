package com.chat.talkMe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "type", nullable = false, length = 50)
    private String type; // MSG, REQUEST, LIKE, STORY, SYSTEM

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    // ── Instagram-style rich fields (who did it + a thumbnail of the target) ──
    // Populated for social notifications (like/comment/follow/post/story/poll) so
    // the UI can show the actor's avatar and the post/reel thumbnail. Null for
    // plain system notifications.
    @Column(name = "actor_id", length = 64)
    private String actorId; // actor's uuid (for opening their profile)

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "actor_avatar", length = 512)
    private String actorAvatar;

    @Column(name = "image_url", length = 512)
    private String imageUrl; // thumbnail of the related post/story/media
}
