package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;

/**
 * Pure, deterministic compatibility scoring (feature #10) — no LLM, no I/O beyond the
 * two user entities. Reused by preference matching, Daily Companion, Secret Crush,
 * Weekly Picks, Icebreakers and the Smart Profile Card.
 */
public interface CompatibilityService {

    /** Full weighted score between two users. */
    CompatibilityScore score(User a, User b);
}
