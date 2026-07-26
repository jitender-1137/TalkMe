package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A calm, wind-down "sleep companion" room as seen by the API (features #26/#27). Messages in
 * these rooms are never recorded (enforced server-side in the send path); this is only the
 * discovery/metadata view.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SleepRoomResponse {

    /** Chat uuid — open/join the room with this. */
    private String id;

    private String name;
    private String description;
    private String category;

    /** SLEEP_COMPANION | LISTENING. */
    private String roomMode;

    private Instant createdAt;
}
