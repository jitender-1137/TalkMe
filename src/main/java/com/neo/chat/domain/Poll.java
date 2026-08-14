package com.neo.chat.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A poll attached to a {@link Post}. Modeling polls as a one-to-one extension of
 * a post lets them reuse the whole feed pipeline (pagination, likes, comments,
 * bookmarks, sharing) for free — a poll post is just a post whose {@code poll}
 * field is non-null.
 */
@Entity
@Table(name = "polls")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Poll extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false, unique = true)
    private Post post;

    @Column(name = "question", nullable = false, length = 120)
    private String question;

    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @OrderBy("orderIndex ASC")
    private List<PollOption> options = new ArrayList<>();
}
