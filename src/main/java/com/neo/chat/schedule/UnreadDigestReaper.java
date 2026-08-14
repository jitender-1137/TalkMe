package com.neo.chat.schedule;

import com.neo.chat.service.UnreadDigestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the daily "unread messages" digest. Default 21:00 (9 PM) Asia/Kolkata; both the
 * cron and the zone are overridable via {@code app.mail.unread-digest.cron} and
 * {@code app.mail.unread-digest.zone}. The actual find-and-send (with per-user dedup) lives
 * in {@link UnreadDigestService}; the whole feature can be turned off with
 * {@code app.mail.unread-digest.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnreadDigestReaper {

    private final UnreadDigestService unreadDigestService;

    /**
     * Sends the daily "unread messages" email digest.
     *
     * <p>Runs on cron {@code ${app.mail.unread-digest.cron:0 0 21 * * *}} in zone
     * {@code ${app.mail.unread-digest.zone:Asia/Kolkata}} (daily at 21:00 IST by default).
     * Delegates the find-and-send with per-user dedup to
     * {@link com.neo.chat.service.UnreadDigestService#sendDailyUnreadDigests()}. Any
     * exception is caught and logged so a failed run never aborts the schedule.</p>
     */
    @Scheduled(
            cron = "${app.mail.unread-digest.cron:0 0 21 * * *}",
            zone = "${app.mail.unread-digest.zone:Asia/Kolkata}")
    public void sendDailyUnreadDigests() {
        try {
            unreadDigestService.sendDailyUnreadDigests();
        } catch (Exception e) {
            log.error("[UnreadDigest] daily run failed", e);
        }
    }
}
