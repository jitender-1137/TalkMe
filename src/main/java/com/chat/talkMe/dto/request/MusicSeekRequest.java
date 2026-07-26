package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /chats/{chatId}/music/seek and POST /chats/{chatId}/music/pause (feature #17).
 *
 * <p>For {@code /seek} the position is required (validated in the service). For {@code /pause}
 * it is optional — when omitted the current stored position is kept.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MusicSeekRequest {

    /** Target playhead position in seconds. */
    private Double positionSec;
}
