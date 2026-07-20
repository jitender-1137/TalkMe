package com.chat.talkMe.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TypingNotification {
    private String userId;
    private String chatUuid;
    private String username;
    private boolean typing;
    // Fine-grained activity: TYPING, RECORDING_AUDIO, RECORDING_VIDEO, or NONE.
    // Null on the legacy typing path (clients treat null as TYPING).
    private String activity;
}
