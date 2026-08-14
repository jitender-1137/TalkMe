package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_presences", indexes = {
        // "Recently online" / last-seen ordered lookups.
        @Index(name = "idx_user_presence_last_seen", columnList = "last_seen_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPresence extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "OFFLINE";

    @Column(name = "last_seen_at")
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "ghost_mode_enabled", nullable = false)
    @Builder.Default
    private boolean ghostModeEnabled = false;

    @Column(name = "invisible_mode_enabled", nullable = false)
    @Builder.Default
    private boolean invisibleModeEnabled = false;

    /**
     * When true, other users never see this user's "last seen" timestamp.
     */
    @Column(name = "hide_last_seen_enabled", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean hideLastSeenEnabled = false;
}
