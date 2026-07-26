package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.ReputationProperties;
import com.chat.talkMe.domain.ReputationEvent;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.event.ReputationSignal;
import com.chat.talkMe.repository.ReputationEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Consumes {@link ReputationSignal}s and writes ledger rows — the anti-abuse gate.
 *
 * <ul>
 *   <li><b>Committed-only</b> — fires AFTER_COMMIT, so rolled-back actions never earn.</li>
 *   <li><b>Dedupe</b> — a unique key per (type, source) makes replays/retries insert once.</li>
 *   <li><b>Diminishing returns</b> — the n-th same-type event today is worth
 *       {@code raw / (1 + factor·n)}, so mass-spamming yields almost nothing.</li>
 *   <li><b>Per-type + global daily caps</b> — bound velocity, time-gating high levels.</li>
 * </ul>
 *
 * Runs async on the shared broadcast pool and opens its own transaction (the original
 * one has already committed). {@code fallbackExecution=true} so it still records when a
 * producer runs outside a transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationEventRecorder {

    private final ReputationEventRepository ledger;
    private final ReputationProperties props;

    // REQUIRES_NEW so a fresh transaction is opened even when the executor's
    // CallerRunsPolicy runs this synchronously on the just-committed producer thread
    // (otherwise the insert would join an already-committed tx and be discarded).
    @Async("broadcastExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSignal(ReputationSignal signal) {
        try {
            final Long userId = signal.userId();
            final ReputationEventType type = signal.type();
            final LocalDate day = LocalDate.ofInstant(signal.occurredAt(), ZoneOffset.UTC);
            final String dedupeKey = buildDedupeKey(signal, day);

            if (ledger.existsByDedupeKey(dedupeKey)) {
                return; // already recorded
            }

            long typeCountToday = ledger.countByUserIdAndTypeAndDayBucket(userId, type, day);
            int diminished = (int) Math.round(
                    type.getRawWeight() / (1.0 + props.getDiminishingFactor() * typeCountToday));

            int typeRemaining = Math.max(0, type.getDailyCap() - ledger.sumAwardedForType(userId, type, day));
            int globalRemaining = Math.max(0, props.getDailyCap() - ledger.sumAwardedForDay(userId, day));

            int awarded = Math.max(0, Math.min(diminished, Math.min(typeRemaining, globalRemaining)));
            // Source-scoped events are additionally bounded by their per-source cap.
            if (signal.sourceRef() != null && !signal.sourceRef().isBlank()) {
                awarded = Math.min(awarded, type.getPerSourceCap());
            }

            ReputationEvent event = ReputationEvent.builder()
                    .userId(userId)
                    .type(type)
                    .rawWeight(type.getRawWeight())
                    .awardedWeight(awarded)
                    .dedupeKey(dedupeKey)
                    .sourceRef(signal.sourceRef())
                    .occurredAt(signal.occurredAt())
                    .dayBucket(day)
                    .counted(awarded > 0)
                    .build();
            ledger.save(event);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent insert of the same dedupe key — the unique constraint did its job.
            log.debug("Reputation dedupe race for {}: {}", signal.type(), dup.getMessage());
        } catch (Exception e) {
            // Reputation must never break the originating flow.
            log.warn("Failed to record reputation signal {}: {}", signal.type(), e.getMessage());
        }
    }

    /**
     * Source-scoped events dedupe by (type, source) — awarded once per source ever.
     * Sourceless daily/aggregate events dedupe by (type, user, day) — once per day.
     */
    private String buildDedupeKey(ReputationSignal signal, LocalDate day) {
        if (signal.sourceRef() != null && !signal.sourceRef().isBlank()) {
            return signal.type().name() + ":" + signal.sourceRef();
        }
        return signal.type().name() + ":" + signal.userId() + ":" + day;
    }
}
