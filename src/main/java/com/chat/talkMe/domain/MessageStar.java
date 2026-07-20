package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A per-user "starred" (saved) message. One row per (message, user); a user can
 * star a message in any chat and review their stars later. Distinct from
 * {@code Message.pinned} which is a single, chat-wide pin.
 */
@Entity
@Table(name = "message_stars", uniqueConstraints = {
        @UniqueConstraint(name = "uk_message_star_msg_user", columnNames = {"message_id", "user_id"})
}, indexes = {
        @Index(name = "idx_message_stars_user", columnList = "user_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageStar extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
