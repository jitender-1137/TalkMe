package com.chat.talkMe.schedule;

import com.chat.talkMe.service.DailyCompanionService;
import com.chat.talkMe.util.BackgroundTaskErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Daily Companion reaper (feature #8). Backstop that flips ACTIVE pairings whose 24h decision
 * window has elapsed to EXPIRED, so a user who never acted stops seeing a stale companion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyCompanionReaper {

    private final DailyCompanionService dailyCompanionService;

    @Scheduled(fixedDelayString = "${app.daily-companion.reaper-ms:60000}")
    public void reap() {
        try {
            int reaped = dailyCompanionService.reapExpired(Instant.now());
            if (reaped > 0) {
                log.debug("[daily-companion] reaper expired {} pairing(s)", reaped);
            }
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[daily-companion] reaper run", e);
        }
    }
}
