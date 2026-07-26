package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A short-lived LiveKit access token for a chat's live-audio room (Phase 6). The client uses
 * {@code wsUrl} + {@code token} to connect via the LiveKit SDK; {@code room} is the chat uuid.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveTokenResponse {
    private String token;
    private String wsUrl;
    private String room;      // chat uuid
    private String identity;  // the joining user's identity (username)
}
