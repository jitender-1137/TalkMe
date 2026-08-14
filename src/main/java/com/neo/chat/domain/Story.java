package com.neo.chat.domain;

import com.neo.chat.enums.PostAudience;
import com.neo.chat.enums.StoryKind;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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

    @Column(name = "caption")
    private String caption;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Who can see this story. EVERYONE = public; FRIENDS = only the author's
    // followers &amp; following (accepted follow in either direction). Backfills
    // existing rows to EVERYONE.
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 16, nullable = false)
    @ColumnDefault("'EVERYONE'")
    @Builder.Default
    private PostAudience audience = PostAudience.EVERYONE;

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
    @ColumnDefault("'VISUAL'")
    @Builder.Default
    private StoryKind kind = StoryKind.VISUAL;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
