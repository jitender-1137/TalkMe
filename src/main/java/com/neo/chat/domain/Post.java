package com.neo.chat.domain;

import com.neo.chat.enums.PostAudience;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "posts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Post extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    // Optional rich-text formatting for text posts: a JSON document of styled
    // blocks ({font, blocks:[{text,size,align,bold,italic}]}). `content` above
    // stays the PLAIN-TEXT version (used for moderation, search, sharing); this
    // is purely presentational and rendered by the client when present.
    @Column(name = "rich_content", columnDefinition = "TEXT")
    private String richContent;

    // Optional caption for a TEXT post — a separate line shown under the post
    // body (whereas media posts keep their caption in `content`). Editing a text
    // post updates this, leaving the formatted body untouched.
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    // Opaque, URL-safe code for shareable post links (Instagram-style /post/{code}).
    @Column(name = "short_code", unique = true, length = 16)
    private String shortCode;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostMedia> media = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostLike> likes = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostBookmark> bookmarks = new ArrayList<>();

    // Optional: present only when this post is a poll.
    @OneToOne(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private Poll poll;

    // Optional soundtrack.
    @Embedded
    private AudioTrack audio;

    // Who can see this post. EVERYONE = public; FRIENDS = only the author's accepted
    // friends (enforced on the profile feed). @ColumnDefault backfills existing rows.
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 16, nullable = false)
    @ColumnDefault("'EVERYONE'")
    @Builder.Default
    private PostAudience audience = PostAudience.EVERYONE;

    /**
     * Optional expiry for a temporary post (feature #22). {@code null} = permanent (the default
     * for every existing/normal post). When set, the post is hidden from feeds once past and is
     * hard-deleted by {@code PostExpiryReaper}.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Whether this post has a TTL and that TTL has elapsed.
     */
    @Transient
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
