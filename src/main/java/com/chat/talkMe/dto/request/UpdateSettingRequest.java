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
}
