package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
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
    @OneToOne(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Poll poll;

    // Optional soundtrack.
    @Embedded
    private AudioTrack audio;

    // Who can see this post. EVERYONE = public; FRIENDS = only the author's accepted
    // friends (enforced on the profile feed). @ColumnDefault backfills existing rows.
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 16, nullable = false)
    @org.hibernate.annotations.ColumnDefault("'EVERYONE'")
    @Builder.Default
    private com.chat.talkMe.enums.PostAudience audience = com.chat.talkMe.enums.PostAudience.EVERYONE;
}
