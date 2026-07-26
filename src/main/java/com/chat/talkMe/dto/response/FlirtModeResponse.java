package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Viewer-relative flirt-mode state for a chat (feature FLIRT_MODE). Also the per-user WS push
 * payload on {@code /user/queue/flirt-mode}. Because {@code myEnabled}/{@code otherEnabled} are
 * relative to the recipient, each participant is sent their OWN instance of this DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlirtModeResponse {

    /** UUID (as String) of the chat this state belongs to. */
    private String chatUuid;

    /** Whether the requesting/receiving user has opted into flirt mode. */
    private boolean myEnabled;

    /** Whether the OTHER participant has opted into flirt mode. */
    private boolean otherEnabled;

    /** True only when both participants have opted in (== myEnabled && otherEnabled). */
    private boolean active;
}
