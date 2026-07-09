package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingResponse {
    private String id;
    private String theme;
    private String language;
    private boolean notificationsEnabled;
    private boolean safeModeEnabled;
    private boolean soundEnabled;
    /** "EVERYONE" or "FRIENDS_ONLY". */
    private String messagingPrivacy;

    // ── Transactional-email preferences ─────────────────────────────────────────
    private boolean emailLoginAlerts;
    private boolean emailUnreadMessages;
    private boolean emailAnnouncements;
}
