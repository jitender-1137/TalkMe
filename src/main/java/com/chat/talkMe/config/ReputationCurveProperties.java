package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Leveling-curve knobs (features #30/#31), kept separate from {@link ReputationProperties}
 * (the earning caps) so the two concerns tune independently.
 *
 * <p>Total lifetime points required to REACH level {@code L} is {@code round(k * (L-1)^p)}.
 * With the defaults ({@code k=6}, {@code p=1.9}) the curve is gentle early and steep late,
 * so the top star ranks demand sustained activity over many months. Level 1 always costs 0.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.reputation.curve")
public class ReputationCurveProperties {

    /** Curve scale. */
    private double k = 6;

    /** Curve exponent. */
    private double p = 1.9;

    /** Hard safety cap so the level loop can never run away. */
    private static final int MAX_LEVEL = 500;

    /** Cumulative lifetime points required to reach level {@code L}. Level 1 costs 0. */
    public long totalXpForLevel(int L) {
        if (L <= 1) {
            return 0L;
        }
        return Math.round(k * Math.pow(L - 1, p));
    }

    /** Highest level {@code L} (>=1) whose {@link #totalXpForLevel(int)} is {@code <= points}. */
    public int levelForPoints(long points) {
        int level = 1;
        for (int L = 2; L <= MAX_LEVEL; L++) {
            if (totalXpForLevel(L) <= points) {
                level = L;
            } else {
                break;
            }
        }
        return level;
    }
}
