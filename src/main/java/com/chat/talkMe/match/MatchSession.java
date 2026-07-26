package com.chat.talkMe.match;

import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.enums.RevealChannel;
import com.chat.talkMe.enums.RevealState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // ── Phase 2: mode + anonymous mask + reveal + timers (all optional; QUICK ignores) ──
    @Builder.Default
    private MatchMode mode = MatchMode.QUICK;

    /** Deterministic aliases shown in Mask chat, e.g. "Moon #247". */
    private String aliasA;
    private String aliasB;

    // Concurrent maps: mutated from both peers' WS threads AND the timer reaper thread.
    /** Per-side reveal state for each channel (PROFILE/VOICE/PHOTO). */
    @Builder.Default
    private Map<RevealChannel, RevealState> revealA = new ConcurrentHashMap<>();
    @Builder.Default
    private Map<RevealChannel, RevealState> revealB = new ConcurrentHashMap<>();
    /** Username that requested the current pending reveal per channel (so only the peer responds). */
    @Builder.Default
    private Map<RevealChannel, String> revealRequestedBy = new ConcurrentHashMap<>();
    @Builder.Default
    private Map<RevealChannel, Integer> revealDeclineCount = new ConcurrentHashMap<>();
    /** Channels already exchanged — terminal, so a reveal can never fire twice. */
    @Builder.Default
    private Set<RevealChannel> revealExchanged = ConcurrentHashMap.newKeySet();

    /** Coffee/Chemistry timer deadline mirror (authoritative copy lives in the Redis ZSET). */
    private volatile Long timerDeadlineEpochMs;
    /** True once the timer has fired and the session is awaiting a post-timer action. */
    @Builder.Default
    private volatile boolean postTimer = false;
    /** Per-user chosen post-timer action (for mutual "continue"/"exchange"). */
    @Builder.Default
    private Map<String, String> timedActionByUser = new ConcurrentHashMap<>();

    /** Active conversation-game session id, if any (Phase 3). */
    private String gameSessionId;
}
