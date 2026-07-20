package com.chat.talkMe.match;

import com.chat.talkMe.enums.ConsentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchSession {
    private String id; // Session ID (UUID)
    private String userA; // Username of User A
    private String userB; // Username of User B
    private Instant createdTime;
    private boolean imagePermissionStatus;

    // Per-session 18+ (explicit text) consent. Scoped to THIS match only — a new
    // match starts a brand-new session with consent reset to NONE, so consent is
    // always re-asked. Kept in-memory like imagePermissionStatus.
    @Builder.Default
    private ConsentStatus consentStatus = ConsentStatus.NONE;
    /** Username that triggered the current pending request (so only the peer responds). */
    private String consentRequestedBy;
    /** Consecutive declines; once it hits the cap, no further requests are sent. */
    @Builder.Default
    private int consentDeclineCount = 0;
}
