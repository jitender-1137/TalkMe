package com.chat.talkMe.websocket;

import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Server-authoritative IDLE → OFFLINE transition.
 *
 * <p>When a user becomes idle (tab backgrounded, or connection dropped), they are
 * marked IDLE with a scheduled offline deadline (see
 * {@link PresenceService#markIdle}). The deadline differs by cause — ~10 minutes
 * for a still-connected but inactive tab, ~5 minutes for a dropped connection.
 * This task runs on a fixed cadence and flips to OFFLINE every idle user whose
 * deadline has passed, independent of any client signal.</p>
 *
 * <p>Complements {@link PresenceWatchdog}: the watchdog detects <em>liveness</em>
 * loss (missed heartbeats) and parks users in IDLE; this reaper enforces the
 * grace window and finalizes them OFFLINE.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdleReaper {

    private final PresenceService presenceService;

    @Scheduled(fixedDelayString = "${presence.idle-reaper.interval-ms:30000}")
    public void reapExpiredIdleUsers() {
        try {
            int reaped = presenceService.reapExpiredIdleUsers();
            if (reaped > 0) {
                log.debug("[Presence] Idle reaper flipped {} user(s) to OFFLINE", reaped);
            }
        } catch (Exception e) {
            log.error("[Presence] Idle reaper run failed", e);
        }
    }

    /**
     * Background staging step one: flip backgrounded users from ONLINE to IDLE once
     * their ONLINE grace window elapses. {@link #reapExpiredIdleUsers()} then
     * finalizes them OFFLINE when the following IDLE grace expires.
     */
    @Scheduled(fixedDelayString = "${presence.away-reaper.interval-ms:30000}")
    public void reapBackgroundedAwayUsers() {
        try {
            int reaped = presenceService.reapBackgroundedAwayUsers();
            if (reaped > 0) {
                log.debug("[Presence] Away reaper flipped {} backgrounded user(s) to IDLE", reaped);
            }
        } catch (Exception e) {
            log.error("[Presence] Away reaper run failed", e);
        }
    }
}
