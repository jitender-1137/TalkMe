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

/**
 * A reply to an {@link AdviceQuestion} in an Anonymous Advice Room (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY INVARIANT: the {@link #author} is persisted ONLY for moderation / abuse handling
 * and author-only deletion. It must NEVER be mapped into any response DTO — advice repliers are
 * permanently anonymous. {@link #parentReplyId} carries the {@code id} of the reply being
 * answered (single-level threading), or {@code null} for a top-level reply.
 */
@Entity
@Table(name = "advice_replies",
        indexes = {
                @Index(name = "idx_advice_replies_question_id", columnList = "question_id"),
                @Index(name = "idx_advice_replies_author_id", columnList = "author_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceReply extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private AdviceQuestion question;

    /**
     * The replying user — stored for moderation / author-only deletion ONLY, never disclosed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    /**
     * The {@code id} of the parent reply this one answers (single-level threading), or null for a
     * top-level reply. A plain scalar (not a relation) — the client threads by matching uuids.
     */
    @Column(name = "parent_reply_id")
    private Long parentReplyId;
}
