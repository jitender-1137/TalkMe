package com.neo.chat.service.impl;

import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.event.ReputationSignal;
import com.neo.chat.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Default {@link ReputationRecorder}: publishes a {@link ReputationSignal} application event that
 * the AFTER_COMMIT ledger recorder consumes. Fire-and-forget with no scoring done here.
 */
@Service
@RequiredArgsConstructor
public class ReputationRecorderImpl implements ReputationRecorder {

    private final ApplicationEventPublisher publisher;

    /**
     * Publishes a reputation signal (stamped with the current instant) for the given user/type.
     * No-op when {@code userId} or {@code type} is null.
     *
     * @param userId    the user to credit; ignored if null
     * @param type      the reputation event type; ignored if null
     * @param sourceRef optional source identifier used for per-source dedupe/caps; may be null
     */
    @Override
    public void record(Long userId, ReputationEventType type, String sourceRef) {
        if (userId == null || type == null) return;
        publisher.publishEvent(new ReputationSignal(userId, type, sourceRef, Instant.now()));
    }
}
