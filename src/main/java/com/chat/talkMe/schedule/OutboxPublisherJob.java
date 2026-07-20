package com.chat.talkMe.schedule;

import com.chat.talkMe.event.OutboxDispatcher;
import com.chat.talkMe.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The safety net that turns "eventually delivered" into "guaranteed delivered".
 *
 * <p>The fast path (publish → consumer) delivers a message and flips its outbox row
 * to PUBLISHED within milliseconds, so in steady state this job finds NOTHING. It
 * only acts on rows still PENDING past the grace window — i.e. messages whose
 * delivery was interrupted by an app crash, a broker outage, or a DLQ exhaustion.
 * For those it re-delivers from the persisted payload, so no message is ever lost.
 *
 * <p>Cheap by design: a lock-free indexed scan for pending ids, then a per-row
 * claim ({@code FOR UPDATE SKIP LOCKED}) so multiple instances cooperate without
 * double-delivering. Delivery itself is idempotent (see {@link MessageDeliveryService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherJob {

    /** Don't touch a row until the fast path has had time to deliver it. */
    private static final Duration GRACE = Duration.ofSeconds(15);
    /** Max rows per tick — bounds work during a recovery surge. */
    private static final int BATCH = 200;
    /** Delivered rows older than this are purged to keep the table small. */
    private static final Duration RETENTION = Duration.ofDays(2);

    private final OutboxEventRepository outboxRepo;
    private final OutboxDispatcher dispatcher;

    /** Re-drive anything the fast path missed. Runs a few seconds after each completes. */
    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:5000}")
    public void redrivePending() {
        try {
            Instant cutoff = Instant.now().minus(GRACE);
            List<Long> ids = outboxRepo.findPendingIds(cutoff, PageRequest.of(0, BATCH));
            if (ids.isEmpty()) {
                return;
            }
            log.warn("[outbox] {} message(s) not delivered by fast path; re-driving", ids.size());
            int ok = 0;
            for (Long id : ids) {
                try {
                    dispatcher.deliverFromOutbox(id);
                    ok++;
                } catch (Exception e) {
                    // Isolated per row — a failure here is retried on the next tick.
                    log.error("[outbox] Re-drive failed for outbox id {}", id, e);
                }
            }
            log.info("[outbox] Re-drive complete: {}/{} delivered", ok, ids.size());
        } catch (Exception e) {
            log.error("[outbox] Poll run failed", e);
        }
    }

    /** Housekeeping: drop long-delivered rows daily. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 30 3 * * *}")
    public void purgeOldPublished() {
        try {
            int deleted = outboxRepo.deletePublishedBefore(Instant.now().minus(RETENTION));
            if (deleted > 0) {
                log.info("[outbox] Purged {} delivered outbox row(s)", deleted);
            }
        } catch (Exception e) {
            log.error("[outbox] Cleanup run failed", e);
        }
    }
}
