package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "match_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_id", nullable = false)
    private User reported;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private MatchSession session;

    @Column(name = "reason", nullable = false, length = 100)
    private String reason;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
}
