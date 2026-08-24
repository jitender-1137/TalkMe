package com.neo.chat.schedule;

import com.neo.chat.service.CommunityHelpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Reaps Community Help requests (feature #9, COMMUNITY_HELP) once their TTL elapses. The feed
 * already hides an expired request the moment {@code expiresAt} passes, so this is the cleanup
 * backstop that flips lingering OPEN requests to RESOLVED. Mirrors {@link PostExpiryReaper}:
 * bounded per tick with its own try/catch so one bad run never aborts the schedule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityHelpReaper {

    private final CommunityHelpService communityHelpService;

    /**
     * Flips OPEN help requests past their TTL to RESOLVED.
     *
     * <p>Runs {@code fixedDelay=${app.community-help.reaper-ms:60000}} (every 60s by default).
     * Delegates to {@link CommunityHelpService#reapExpired(Instant)} with the current instant and
     * only logs when at least one request was reaped. Any exception is caught so one failed tick
     * never aborts the schedule.</p>
     */
    @Scheduled(fixedDelayString = "${app.community-help.reaper-ms:60000}")
    public void reap() {
        try {
            int reaped = communityHelpService.reapExpired(Instant.now());
            if (reaped > 0) {
                log.debug("[community-help] reaper resolved {} expired request(s)", reaped);
            }
        } catch (Exception e) {
            log.error("[community-help] reaper run failed", e);
        }
    }
}
