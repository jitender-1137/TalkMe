package com.chat.talkMe.event;

import com.chat.talkMe.enums.ReputationEventType;

import java.time.Instant;

/**
 * A committed action worth reputation. Published via {@code ApplicationEventPublisher}
 * (typically through {@code ReputationRecorder}) and consumed AFTER_COMMIT by the ledger
 * recorder, so only actions whose transaction actually committed ever earn points.
 *
 * @param userId    the user earning the reputation
 * @param type      the contributing action
 * @param sourceRef stable id of the source (message/post/friend/event uuid), or null for
 *                  daily/aggregate events — drives the dedupe key
 * @param occurredAt when it happened
 */
public record ReputationSignal(Long userId, ReputationEventType type, String sourceRef, Instant occurredAt) {
}
