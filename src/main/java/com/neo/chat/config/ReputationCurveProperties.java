package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Leveling-curve knobs (features #30/#31), kept separate from {@link ReputationProperties}
 * (the earning caps) so the two concerns tune independently.
 *
 * <p>Total lifetime points required to REACH level {@code L} is {@code round(k * (L-1)^p)}.
 * With the defaults ({@code k=6}, {@code p=1.9}) the curve is gentle early and steep late,
 * so the top star ranks demand sustained activity over many months. Level 1 always costs 0.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "app.reputation.curve")
public class ReputationCurveProperties {

    /**
     * Curve scale.
     */
    private final double k;

    /**
     * Curve exponent.
     */
    private final double p;

    /**
     * Hard safety cap so the level loop can never run away.
     */
    private static final int MAX_LEVEL = 500;

    /**
     * Production default curve ({@code k=6}, {@code p=1.9}) — the values used when no
     * property is set.
     */
    public ReputationCurveProperties() {
        this(6, 1.9);
    }

    /**
     * Binds {@code app.reputation.curve.*}.
     *
     * @param k curve scale (default {@code 6})
     * @param p curve exponent (default {@code 1.9})
     */
    @ConstructorBinding
    public ReputationCurveProperties(@DefaultValue("6") double k,
                                     @DefaultValue("1.9") double p) {
        this.k = k;
        this.p = p;
    }

    /**
     * Cumulative lifetime points required to reach level {@code L}. Level 1 costs 0.
     */
    public long totalXpForLevel(int L) {
        if (L <= 1) {
            return 0L;
        }
        return Math.round(k * Math.pow(L - 1, p));
    }

    /**
     * Highest level {@code L} (>=1) whose {@link #totalXpForLevel(int)} is {@code <= points}.
     */
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
