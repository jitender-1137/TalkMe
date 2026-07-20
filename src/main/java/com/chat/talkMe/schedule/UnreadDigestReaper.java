package com.chat.talkMe.schedule;

import com.chat.talkMe.service.UnreadDigestService;
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
