package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.enums.SendPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Per-group behaviour settings, embedded directly into {@code chats} (1:1 with
 * the chat, always loaded with it, never queried alone). Columns carry
 * {@code @ColumnDefault} so ddl-auto can add these NOT NULL columns to the
 * existing, non-empty chats table (Postgres backfills existing rows).
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSettings {

    /** Who may post. CHANNEL forces ADMINS_ONLY at the service layer. */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'EVERYONE'")
    @Column(name = "settings_who_can_send", nullable = false, length = 20)
    @Builder.Default
    private SendPolicy whoCanSend = SendPolicy.EVERYONE;

    /** Minimum role required to add members. */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MEMBER'")
    @Column(name = "settings_who_can_add_members", nullable = false, length = 20)
    @Builder.Default
    private MemberRole whoCanAddMembers = MemberRole.MEMBER;

    /** Minimum role required to edit name/description/avatar/settings. */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ADMIN'")
    @Column(name = "settings_who_can_edit_info", nullable = false, length = 20)
    @Builder.Default
    private MemberRole whoCanEditInfo = MemberRole.ADMIN;

    /** Minimum role required to pin/unpin messages. */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ADMIN'")
    @Column(name = "settings_who_can_pin", nullable = false, length = 20)
    @Builder.Default
    private MemberRole whoCanPin = MemberRole.ADMIN;

    /** Minimum seconds between a member's messages; 0 = off. */
    @ColumnDefault("0")
    @Column(name = "settings_slow_mode_seconds", nullable = false)
    @Builder.Default
    private int slowModeSeconds = 0;
}
