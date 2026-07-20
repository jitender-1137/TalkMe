package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceResponse {
    private String username;
    private String status;
    private String lastSeenAt;
    private boolean ghostModeEnabled;
    private boolean invisibleModeEnabled;
    private boolean hideLastSeenEnabled;
}
