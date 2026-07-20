package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MessagingPrivacy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSetting extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "theme", nullable = false, length = 30)
    @Builder.Default
    private String theme = "SYSTEM";

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = "en";

    @Column(name = "notifications_enabled", nullable = false)
    @Builder.Default
    private boolean notificationsEnabled = true;

    @Column(name = "safe_mode_enabled", nullable = false)
    @Builder.Default
    private boolean safeModeEnabled = true;

    @Column(name = "sound_enabled", nullable = false)
    @Builder.Default
    private boolean soundEnabled = true;

    /**
     * Who may message this user. @ColumnDefault gives existing rows a safe DB default
     * when ddl-auto adds this NOT NULL column. Do NOT also put "default ..." in
     * columnDefinition — Hibernate would then emit two DEFAULT clauses and Postgres
     * rejects the ALTER ("multiple default values specified").
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'EVERYONE'")
    @Column(name = "messaging_privacy", nullable = false, length = 20)
    @Builder.Default
    private MessagingPrivacy messagingPrivacy = MessagingPrivacy.EVERYONE;

    /**
     * Who may add this user to a group/room directly. EVERYONE (default) / FRIENDS_ONLY /
     * NOBODY. When the adder isn't permitted, they send a group invite instead of a direct
     * add (see the group-invite flow).
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'EVERYONE'")
    @Column(name = "group_add_privacy", nullable = false, length = 20)
    @Builder.Default
    private com.chat.talkMe.enums.GroupAddPrivacy groupAddPrivacy = com.chat.talkMe.enums.GroupAddPrivacy.EVERYONE;

    // ── Transactional-email preferences ─────────────────────────────────────────
    // User-controllable opt-outs for NON-critical emails. Security/verification/reset
    // emails are always sent and are NOT gated by these. @ColumnDefault('true') gives
    // existing rows a value when ddl-auto adds the NOT NULL column (do NOT also put a
    // "default" in columnDefinition — see the messaging_privacy note above).

    /** New-sign-in security alert emails. */
    @ColumnDefault("true")
    @Column(name = "email_login_alerts", nullable = false)
    @Builder.Default
    private boolean emailLoginAlerts = true;

    /** "You have unread messages" digest emails. */
    @ColumnDefault("true")
    @Column(name = "email_unread_messages", nullable = false)
    @Builder.Default
    private boolean emailUnreadMessages = true;

    /** Product news / announcement emails. */
    @ColumnDefault("true")
    @Column(name = "email_announcements", nullable = false)
    @Builder.Default
    private boolean emailAnnouncements = true;
}
