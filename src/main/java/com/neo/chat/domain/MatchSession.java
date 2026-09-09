package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "match_sessions",
        indexes = {
                @Index(name = "idx_match_sessions_host_id", columnList = "host_id"),
                @Index(name = "idx_match_sessions_peer_id", columnList = "peer_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "peer_id", nullable = false)
    private User peer;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
