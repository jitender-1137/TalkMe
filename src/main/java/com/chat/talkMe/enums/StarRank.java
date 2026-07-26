package com.chat.talkMe.enums;

/**
 * Cosmetic star tier a user displays, derived purely from their current {@code level}
 * (features #30/#31). Ranks are ordered ascending by {@link #minLevel}; a user's rank is
 * the highest rank whose {@code minLevel <= level}. This is decoration only — it must
 * NEVER gate features or limits.
 */
public enum StarRank {
    BRONZE_STAR(1),
    SILVER_STAR(10),
    GOLD_STAR(20),
    PLATINUM_STAR(30),
    DIAMOND_STAR(40),
    MASTER(55),
    ELITE(70),
    LEGEND(85),
    COSMIC(100),
    NEO_STAR(150);

    private final int minLevel;

    StarRank(int minLevel) {
        this.minLevel = minLevel;
    }

    public int getMinLevel() {
        return minLevel;
    }

    /**
     * Highest rank whose {@code minLevel <= level}. Never null: BRONZE_STAR has
     * {@code minLevel == 1}, so any level >= 1 resolves. Levels below 1 clamp to BRONZE_STAR.
     */
    public static StarRank forLevel(int level) {
        StarRank result = BRONZE_STAR;
        for (StarRank rank : values()) {
            if (rank.minLevel <= level) {
                result = rank;
            } else {
                break;
            }
        }
        return result;
    }
}
