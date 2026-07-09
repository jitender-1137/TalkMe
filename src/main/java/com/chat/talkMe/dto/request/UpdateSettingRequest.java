package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSettingRequest {
    @Size(max = 30, message = "Theme must not exceed 30 characters")
    private String theme;

    @Size(max = 10, message = "Language code must not exceed 10 characters")
    private String language;

    private Boolean notificationsEnabled;

    private Boolean safeModeEnabled;

    private Boolean soundEnabled;

    /** "EVERYONE" or "FRIENDS_ONLY"; null leaves it unchanged. */
    @Size(max = 20, message = "Invalid messaging privacy value")
    private String messagingPrivacy;

    // ── Transactional-email preferences (null ⇒ unchanged) ──────────────────────
    /** New-sign-in security alert emails. */
    private Boolean emailLoginAlerts;

    /** "You have unread messages" digest emails. */
    private Boolean emailUnreadMessages;

    /** Product news / announcement emails. */
    private Boolean emailAnnouncements;
}
