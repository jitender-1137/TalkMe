package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserReputation;
import com.chat.talkMe.dto.response.ReputationResponse;
import com.chat.talkMe.dto.response.ReputationWhyResponse;

/**
 * Core reputation engine (features #30/#31). Folds the append-only {@link com.chat.talkMe.domain.ReputationEvent}
 * ledger into a per-user {@link UserReputation} snapshot (level, star rank, prestige) and
 * serves cosmetic display DTOs. Rewards are decoration only — never gate features by them.
 */
public interface ReputationService {

    /** Cosmetic snapshot for the caller; lazily creates a BRONZE/level-1 record on first read. */
    ReputationResponse getMine(User user);

    /** Cosmetic snapshot for any user by uuid (for profile viewing). */
    ReputationResponse getFor(String userUuid);

    /** Opaque contributor explainer (labels + coarse magnitude only, no weights). */
    ReputationWhyResponse why(User user);

    /** Prestige reset (requires level &gt;= 100). Preserves all-time totals + lifetime stats. */
    ReputationResponse prestige(User user);

    /**
     * Incrementally fold new ledger rows into the user's snapshot and recompute level/star.
     * Idempotent via the {@code lastLedgerIdApplied} cursor. Pushes a WS level-up on increase.
     */
    UserReputation recomputeFor(User user);
}
