package com.neo.chat.schedule;

import com.neo.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Server-side backstop for Telegram-style self-destruct / view-once media.
 *
 * <p>The receiver's client normally reports back (a {@code consume} call) the moment a
 * countdown hits 0 or a view-once viewer closes, destroying the media instantly. This
 * reaper guarantees destruction even when that never happens — the client crashed, went
 * offline, or a view-once was opened and abandoned. It destroys any message a receiver
 * has armed whose deadline (armedAt + N seconds, or a fixed grace for view-once) has
 * passed. The expensive part (file delete + broadcast) only touches the tiny set of
 * currently-armed, not-yet-destroyed messages.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfDestructReaper {

    private final MessageService messageService;

    /**
     * Destroys self-destruct / view-once media whose armed deadline has passed.
     *
     * <p>Runs {@code fixedDelay=${app.self-destruct.reaper-ms:5000}} (every 5s by default).
     * Delegates to
     * {@link com.neo.chat.service.MessageService#reapExpiredSelfDestruct(java.time.Instant)}
     * with the current instant; that scan only touches the small set of armed,
     * not-yet-destroyed messages. Logs only when at least one message was destroyed, and any
     * exception is caught so one failed tick never aborts the schedule.</p>
     */
    @Scheduled(fixedDelayString = "${app.self-destruct.reaper-ms:5000}")
    public void reap() {
        try {
            int reaped = messageService.reapExpiredSelfDestruct(Instant.now());
            if (reaped > 0) {
                log.debug("[self-destruct] reaper destroyed {} expired media message(s)", reaped);
            }
        } catch (Exception e) {
            log.error("[self-destruct] reaper run failed", e);
        }
    }
}
