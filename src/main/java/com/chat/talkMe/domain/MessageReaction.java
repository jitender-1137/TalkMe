package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "message_reactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "emoji", nullable = false, length = 32)
    private String emoji;
}
