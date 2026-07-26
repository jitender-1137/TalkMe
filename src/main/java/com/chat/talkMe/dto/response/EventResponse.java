package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A Midnight Event (feature #24) as seen by a viewer. Carries the event fields, a compact host
 * summary, live RSVP counts, the viewer's own RSVP ({@code myRsvp}, null if none) and, once the
 * event is LIVE, the {@code roomChatUuid} the client uses to open/join the room.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {

    private String eventUuid;
    private String title;
    private String description;
    private Instant startAt;
    private Instant endAt;
    private String category;
    private String status;
    private String roomChatUuid;
    private int maxAttendees;

    // ── Host summary ──────────────────────────────────────────────────────────
    private String hostUuid;
    private String hostName;
    private String hostUsername;
    private String hostAvatar;
    private boolean hostedByMe;

    // ── Counts + viewer state ─────────────────────────────────────────────────
    private long goingCount;
    private long interestedCount;
    /** The viewer's RSVP status (GOING|INTERESTED|DECLINED), or null if they haven't RSVP'd. */
    private String myRsvp;
    private boolean attended;
}
