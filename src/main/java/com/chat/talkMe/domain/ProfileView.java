package com.chat.talkMe.domain;

import com.chat.talkMe.enums.ProfileViewType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A record that {@code viewer} opened {@code viewed}'s profile / profile photo.
 * One row per (viewer, viewed) pair — repeat views bump {@code viewCount} and
 * {@code lastViewedAt} and re-flag {@code seen=false} so they re-surface in the
 * viewed user's "who viewed me" badge.
 */
@Entity
@Table(name = "profile_views", uniqueConstraints = @UniqueConstraint(columnNames = {"viewer_id", "viewed_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileView extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viewer_id", nullable = false)
    private User viewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viewed_id", nullable = false)
    private User viewed;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_view_type", length = 20)
    private ProfileViewType lastViewType;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private int viewCount = 1;

    @Column(name = "last_viewed_at", nullable = false)
    @Builder.Default
    private Instant lastViewedAt = Instant.now();

    /** False until the viewed user has opened their "who viewed me" list (drives the badge). */
    @Column(name = "seen", nullable = false)
    @Builder.Default
    private boolean seen = false;
}
