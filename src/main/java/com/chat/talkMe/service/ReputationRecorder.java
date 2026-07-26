package com.chat.talkMe.service;

import com.chat.talkMe.enums.ReputationEventType;

/**
 * One-line seam producers use to award reputation, e.g.
 * {@code reputationRecorder.record(userId, ReputationEventType.PROFILE_COMPLETED, String.valueOf(userId));}
 * It publishes a {@code ReputationSignal} bound to the current transaction; the ledger
 * recorder applies dedupe + caps AFTER_COMMIT. Safe to call from any service.
 */
public interface ReputationRecorder {

    void record(Long userId, ReputationEventType type, String sourceRef);
}
