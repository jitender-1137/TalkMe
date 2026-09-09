package com.neo.chat.service.impl;

import com.neo.chat.event.ReputationSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 * <p>
 * Runs async on the shared broadcast pool and delegates the ledger write to
 * {@link ReputationLedgerWriter}, which opens its own REQUIRES_NEW transaction on the async
 * thread (the original one has already committed). {@code fallbackExecution=true} so it still
 * records when a producer runs outside a transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationEventRecorder {

    private final ReputationLedgerWriter writer;

    /**
     * Handles one {@link ReputationSignal} AFTER_COMMIT by delegating to the transactional
     * {@link ReputationLedgerWriter#record(ReputationSignal)}. A concurrent duplicate insert is
     * caught via the unique constraint and any other error is swallowed so reputation never breaks
     * the originating flow.
     *
     * @param signal the reputation signal (user, type, optional source ref, occurrence time)
     */
    @Async("broadcastExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSignal(ReputationSignal signal) {
        try {
            writer.record(signal);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent insert of the same dedupe key — the unique constraint did its job.
            log.debug("Reputation dedupe race for {}: {}", signal.type(), dup.getMessage());
        } catch (Exception e) {
            // Reputation must never break the originating flow.
            log.warn("Failed to record reputation signal {}: {}", signal.type(), e.getMessage());
        }
    }
}
