package com.neo.chat.domain;

import jakarta.persistence.Column;
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

/**
 * A pending invitation for a user to join a group/room, created when an adder is
 * NOT allowed to add the invitee directly (per the invitee's "who can add me"
 * setting). The invitee accepts or declines it; accepting joins them to the group.
 */
@Entity
@Table(
        name = "group_invites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "invitee_id"}),
        indexes = {
                @Index(name = "idx_group_invites_inviter_id", columnList = "inviter_id"),
                @Index(name = "idx_group_invites_invitee_id", columnList = "invitee_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupInvite extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_id", nullable = false)
    private User invitee;

    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING"; // PENDING | ACCEPTED | DECLINED
}
