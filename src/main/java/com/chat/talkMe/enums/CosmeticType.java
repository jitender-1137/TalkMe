package com.chat.talkMe.enums;

/**
 * Kind of cosmetic reward (Phase 4 gamification surface). Purely decorative — a cosmetic's
 * type also acts as its equip <b>slot</b>: a user may have at most one equipped cosmetic per
 * type. Cosmetics NEVER gate features or limits.
 */
public enum CosmeticType {
    FRAME,
    BORDER,
    BADGE,
    CHAT_BUBBLE,
    PROFILE_THEME,
    NAME_GLOW,
    EMOJI_PACK
}
