package com.neo.chat.domain;

import com.neo.chat.enums.HelpCategory;
import com.neo.chat.enums.HelpStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A short-lived, city-scoped practical question in the Community Help feed (feature #9,
 * COMMUNITY_HELP) — e.g. "my train was cancelled, alternative route?" or "is this road open?".
 *
 * <p>Unlike anonymous advice rooms this is <strong>not anonymous</strong>: the asker's public
 * info is surfaced so neighbours who answer can follow up. Requests are ephemeral — {@link #expiresAt}
 * (typically {@code now + 6h}) bounds their lifetime; the feed hides expired rows the moment the TTL
 * passes and the {@code CommunityHelpReaper} flips lingering OPEN → RESOLVED as a cleanup backstop.
 * Answers thread off a request via {@link HelpAnswer}; {@link #answerCount} is a denormalised counter.
 */
@Entity
@Table(name = "community_help_requests",
        indexes = @Index(name = "idx_community_help_requests_asker_id", columnList = "asker_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpRequest extends BaseEntity {

    /**
     * The user who posted the question — the only party allowed to mark it resolved.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asker_id", nullable = false)
    private User asker;

    /**
     * City the question is scoped to (free-text string; no geo/lat-long exists). Defaults to the
     * asker's own city at post time and is matched case-insensitively in the feed.
     */
    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    @ColumnDefault("'OTHER'")
    @Builder.Default
    private HelpCategory category = HelpCategory.OTHER;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'OPEN'")
    @Builder.Default
    private HelpStatus status = HelpStatus.OPEN;

    /**
     * When the request stops accepting answers and drops out of the feed. Set at post time.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Denormalised count of answers, incremented on each accepted answer.
     */
    @Column(name = "answer_count", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int answerCount = 0;
}
