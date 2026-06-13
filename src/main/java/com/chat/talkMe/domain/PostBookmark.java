package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_bookmarks", uniqueConstraints = @UniqueConstraint(columnNames = {"post_id", "user_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostBookmark extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
