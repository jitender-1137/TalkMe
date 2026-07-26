package com.chat.talkMe.domain;

import com.chat.talkMe.enums.FeedbackStatus;
import com.chat.talkMe.enums.FeedbackType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

/**
 * A single piece of user feedback captured across the app (logout, account
 * deletion, leaving a group/room, or a voluntary "Share feedback" action).
 * Read-only for users once submitted; surfaced to super-admins in the dashboard.
 */
@Entity
@Table(name = "feedback")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback extends BaseEntity {

    /** The author of the feedback. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 1–5 star rating; 0 means "no rating given" (allowed for lightweight prompts). */
    @Column(name = "rating", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int rating = 0;

    /** Optional short reason chip (e.g. "Too many notifications"). */
    @Column(name = "reason", length = 120)
    private String reason;

    /** Free-text comment. */
    @Column(name = "comment", length = 4000)
    private String comment;

    /** Which surface the feedback came from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    @ColumnDefault("'MANUAL'")
    @Builder.Default
    private FeedbackType type = FeedbackType.MANUAL;

    /** Optional human context (e.g. the group/room name the user just left). */
    @Column(name = "context_ref", length = 160)
    private String contextRef;

    /** Optional client platform hint ("web", "ios", "android"). */
    @Column(name = "platform", length = 32)
    private String platform;

    /** Admin triage state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    @ColumnDefault("'NEW'")
    @Builder.Default
    private FeedbackStatus status = FeedbackStatus.NEW;
}
