package com.chat.talkMe.enums;

/**
 * How a cosmetic becomes unlockable (Phase 4 gamification surface). The paired
 * {@code unlockThreshold} on {@link com.chat.talkMe.domain.UnlockableCosmetic} is interpreted
 * per type:
 * <ul>
 *   <li>{@code LEVEL}    — unlocked when {@code UserReputation.level >= threshold}.</li>
 *   <li>{@code STAR}     — unlocked when the user's {@link StarRank} ordinal &gt;= threshold.</li>
 *   <li>{@code PRESTIGE} — unlocked when {@code UserReputation.prestigeCount >= threshold}.</li>
 *   <li>{@code BADGE}    — unlocked when the user owns the referenced badge. There is no badge
 *       inventory yet, so BADGE cosmetics stay locked until one exists.</li>
 *   <li>{@code SEASONAL} — event/admin-granted; never auto-unlocked by reputation.</li>
 * </ul>
 * All unlocks are COSMETIC — they must never gate features or limits.
 */
public enum CosmeticUnlockType {
    LEVEL,
    STAR,
    PRESTIGE,
    BADGE,
    SEASONAL
}
