package com.neo.chat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "post_comment_likes", uniqueConstraints = @UniqueConstraint(columnNames = {"comment_id", "user_id"}),
        indexes = @Index(name = "idx_post_comment_likes_user_id", columnList = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostCommentLike extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private PostComment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
