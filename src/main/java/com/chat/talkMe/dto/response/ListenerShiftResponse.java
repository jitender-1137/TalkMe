package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A volunteer listener shift as seen by the API (features #26/#27). Doubles as the payload for
 * both the listener's own view (goAvailable/end) and the requester's match result (the listener
 * they've been connected with + the {@link #roomChatUuid} to open).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListenerShiftResponse {

    /** Shift uuid. */
    private String id;

    /** Listener identity (the volunteer). */
    private String listenerId;
    private String listenerName;
    private String listenerUsername;
    private String listenerAvatar;

    /** AVAILABLE | ENGAGED | ENDED. */
    private String status;

    /** Uuid of the LISTENING-mode room this shift is bound to (null while merely available). */
    private String roomChatUuid;

    private int peopleHelped;

    private Instant startedAt;
    private Instant endedAt;
}
