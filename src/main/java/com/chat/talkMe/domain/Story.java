package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "stories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Story extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "media_url", nullable = false, length = 512)
    private String mediaUrl;

    @Column(name = "caption", length = 255)
    private String caption;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Who can see this story. EVERYONE = public; FRIENDS = only the author's
    // followers &amp; following (accepted follow in either direction). Backfills
    // existing rows to EVERYONE.
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 16, nullable = false)
    @org.hibernate.annotations.ColumnDefault("'EVERYONE'")
    @Builder.Default
    private com.chat.talkMe.enums.PostAudience audience = com.chat.talkMe.enums.PostAudience.EVERYONE;

    // Optional soundtrack.
    @Embedded
    private AudioTrack audio;

    /**
     * Medium of this story (feature #21). VISUAL = classic image/video (the default; every
     * existing row backfills here). VOICE = an audio-only status whose {@code mediaUrl} is a
     * validated voice clip played through the shared audio bar.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 12, nullable = false)
    @org.hibernate.annotations.ColumnDefault("'VISUAL'")
    @Builder.Default
    private com.chat.talkMe.enums.StoryKind kind = com.chat.talkMe.enums.StoryKind.VISUAL;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
