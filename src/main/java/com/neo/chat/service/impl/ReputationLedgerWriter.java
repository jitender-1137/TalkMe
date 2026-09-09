package com.neo.chat.service.impl;

import com.neo.chat.config.ReputationProperties;
import com.neo.chat.domain.ReputationEvent;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.event.ReputationSignal;
import com.neo.chat.repository.ReputationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Transactional half of the reputation ledger gate: dedupes, applies diminishing returns and
 * the per-type / per-source / global daily caps, then inserts exactly one ledger row.
 *
 * <p>Kept as its own bean so {@link ReputationEventRecorder} can stay purely {@code @Async}
 * and call this through the Spring proxy — the transaction is then scoped to the async worker
 * thread instead of being declared on the same method as {@code @Async}.</p>
 *
 * <p>REQUIRES_NEW so a fresh transaction is opened even when the executor's CallerRunsPolicy
 * runs this synchronously on the just-committed producer thread (otherwise the insert would
 * join an already-committed tx and be discarded).</p>
 */
@Component
@RequiredArgsConstructor
public class ReputationLedgerWriter {

    private final ReputationEventRepository ledger;
    private final ReputationProperties props;

    /**
     * Records one signal: no-op when the dedupe key already exists; otherwise computes the capped
     * award and saves a ledger row (marked counted when > 0).
     *
     * @param signal the reputation signal (user, type, optional source ref, occurrence time)
     * @throws org.springframework.dao.DataIntegrityViolationException on a concurrent duplicate insert
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ReputationSignal signal) {
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

        int awarded = Math.clamp(diminished, 0, Math.min(typeRemaining, globalRemaining));
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
