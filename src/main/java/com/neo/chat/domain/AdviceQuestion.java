package com.neo.chat.domain;

import com.neo.chat.enums.AdviceCategory;
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

/**
 * A question posted in an Anonymous Advice Room (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY INVARIANT: the {@link #author} is persisted ONLY for moderation / abuse handling
 * and author-only deletion. It must NEVER be mapped into any response DTO — advice questions and
 * their askers are permanently anonymous to every other user. See
 * {@code AdviceRoomServiceImpl.toResponse}.
 */
@Entity
@Table(name = "advice_questions",
        indexes = @Index(name = "idx_advice_questions_author_id", columnList = "author_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceQuestion extends BaseEntity {

    /**
     * The asking user — stored for moderation / author-only deletion ONLY, never disclosed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    @ColumnDefault("'OTHER'")
    @Builder.Default
    private AdviceCategory category = AdviceCategory.OTHER;

    /**
     * Denormalised count of non-deleted replies, kept in step as replies are added/removed.
     */
    @Column(name = "reply_count", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int replyCount = 0;
}
