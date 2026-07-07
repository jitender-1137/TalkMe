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

    // Optional soundtrack.
    @Embedded
    private AudioTrack audio;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
