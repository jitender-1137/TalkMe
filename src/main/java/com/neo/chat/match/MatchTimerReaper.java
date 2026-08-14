package com.neo.chat.match;

import com.neo.chat.util.BackgroundTaskErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives Coffee/Chemistry timers (features #7/#14). Polls the Redis deadline ZSETs on a
 * short cadence and fires TIME_UP / rotates Chemistry prompts — server-authoritative, so
 * the client clock can never unlock post-timer actions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchTimerReaper {

    private final MatchTimerService matchTimerService;

    /**
     * Scheduled tick (default every 1000ms) that delegates to
     * {@link MatchTimerService#reapDue()} to fire due time-ups and rotate Chemistry prompts.
     * Any exception is caught and logged so a single failure never halts the schedule.
     */
    @Scheduled(fixedDelayString = "${match.timer-reaper.interval-ms:1000}")
    public void reap() {
        try {
            matchTimerService.reapDue();
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[Match] timer reaper", e);
        }
    }
}
