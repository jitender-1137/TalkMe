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
 * A neighbour's reply to a {@link HelpRequest} in the Community Help feed (feature #9,
 * COMMUNITY_HELP). Answers are not anonymous — the answerer's public info is surfaced so the asker
 * can follow up. Threading mirrors an advice reply: each answer holds a {@code @ManyToOne} back to
 * its request.
 */
@Entity
@Table(name = "community_help_answers",
        indexes = {
                @Index(name = "idx_community_help_answers_help_request_id", columnList = "help_request_id"),
                @Index(name = "idx_community_help_answers_answerer_id", columnList = "answerer_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpAnswer extends BaseEntity {

    /**
     * The request this answer threads off.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "help_request_id", nullable = false)
    private HelpRequest helpRequest;

    /**
     * The user who answered.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answerer_id", nullable = false)
    private User answerer;

    @Column(name = "body", nullable = false, length = 500)
    private String body;
}
