package com.chat.talkMe.websocket;

import com.chat.talkMe.util.BackgroundTaskErrors;

import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Server-authoritative presence timeout detection.
 *
 * <p>The client cannot be trusted to announce that it has gone away — a closed tab,
 * crash, sleeping device, or dropped network may never deliver a WebSocket
 * disconnect. So the server runs its own watchdog: clients send an application-level
 * heartbeat every 30s (refreshing a Redis liveness timestamp), and this scheduled
 * task marks OFFLINE anyone whose last heartbeat is older than the timeout —
 * regardless of any disconnect event. The 60s timeout tolerates one missed beat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceWatchdog {

    private final PresenceService presenceService;

    /** Two missed 30s heartbeats → offline. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Scheduled(fixedDelayString = "${presence.watchdog.interval-ms:20000}")
    public void reapStaleUsers() {
        try {
            int reaped = presenceService.reapTimedOutUsers(TIMEOUT);
            if (reaped > 0) {
                log.debug("[Presence] Watchdog reaped {} timed-out user(s)", reaped);
            }
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[Presence] Watchdog", e);
        }
    }
}
