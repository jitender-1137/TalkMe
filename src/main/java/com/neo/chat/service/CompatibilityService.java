package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;

/**
 * Pure, deterministic compatibility scoring (feature #10) — no LLM, no I/O beyond the
 * two user entities. Reused by preference matching, Daily Companion, Secret Crush,
 * Weekly Picks, Icebreakers and the Smart Profile Card.
 */
public interface CompatibilityService {

    /**
     * Full weighted score between two users.
     */
    CompatibilityScore score(User a, User b);
}
