package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request a LiveKit token for the given chat's live-audio room (Phase 6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveTokenRequest {
    @NotBlank(message = "chatUuid is required")
    private String chatUuid;
}
