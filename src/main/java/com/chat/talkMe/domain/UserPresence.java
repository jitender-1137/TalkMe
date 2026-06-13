package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "user_presences")
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
}
