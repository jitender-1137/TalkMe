package com.chat.talkMe.match;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finalizes match teardown after the reconnect grace window.
 *
 * <p>When a socket drops mid-matchmaking, {@link DisconnectHandlerService#scheduleDisconnect}
 * holds the session/queue spot and records a deadline instead of tearing down. This task
 * polls on a fixed cadence and runs the real teardown (notifying the peer
 * STRANGER_DISCONNECTED) for anyone whose grace has elapsed without reconnecting. A
 * reconnect within the window cancels the deadline via
 * {@link DisconnectHandlerService#cancelDisconnect} before this ever fires.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchDisconnectReaper {

    private final DisconnectHandlerService disconnectHandlerService;

    @Scheduled(fixedDelayString = "${match.disconnect-reaper.interval-ms:3000}")
    public void reapExpiredDisconnects() {
        try {
            int reaped = disconnectHandlerService.reapExpiredDisconnects();
            if (reaped > 0) {
                log.debug("[Match] disconnect reaper tore down {} expired session(s)", reaped);
            }
        } catch (Exception e) {
            log.error("[Match] disconnect reaper run failed", e);
        }
    }
}
