package com.chat.talkMe.match;

import java.util.Objects;

/**
 * Deterministic anonymous aliases for Mask chat, e.g. "Moon #247". Deterministic per
 * (sessionId, slot) so the alias is stable across reconnects/buffer replays, and the two
 * peers get distinct aliases (slot A/B mixed into the hash). Never derived from identity.
 */
public final class AliasGenerator {

    private static final String[] WORDS = {
            "Moon", "Fox", "Nova", "Wolf", "Echo", "Comet", "Lynx", "Raven", "Ember", "Onyx",
            "Sage", "Aster", "Vega", "Zephyr", "Koi", "Iris", "Orbit", "Frost", "Cobra", "Lumen",
    };

    private AliasGenerator() {}

    /** slot 0 = userA, slot 1 = userB. */
    public static String alias(String sessionId, int slot) {
        // Mask the sign bit — Math.abs(Integer.MIN_VALUE) stays negative and would yield a
        // negative array index. floorMod + a masked hash guarantee non-negative results.
        int h = Objects.hash(sessionId, slot) & 0x7fffffff;
        String word = WORDS[Math.floorMod(h, WORDS.length)];
        int number = (h / WORDS.length) % 900 + 1; // 1..900
        return word + " #" + number;
    }
}
