package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /chats/{chatId}/music/react (feature #17): a lightweight emoji reaction
 * to whatever is currently playing. Purely ephemeral — broadcast, never persisted beyond
 * refreshing the session TTL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MusicReactRequest {

    /** The reaction emoji (short). */
    private String emoji;
}
