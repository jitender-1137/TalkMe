package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A pending invitation for a user to join a group/room, created when an adder is
 * NOT allowed to add the invitee directly (per the invitee's "who can add me"
 * setting). The invitee accepts or declines it; accepting joins them to the group.
 */
@Entity
@Table(
        name = "group_invites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "invitee_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupInvite extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_id", nullable = false)
    private User invitee;

    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING"; // PENDING | ACCEPTED | DECLINED
}
