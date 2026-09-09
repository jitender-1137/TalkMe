package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable reputation knobs (features #30/#31). The global {@code dailyCap} is the primary
 * time-gate: combined with per-type caps it bounds how fast anyone can climb, making high
 * levels unreachable in days. The leveling curve (k/p), decay, and retention are added in
 * Phase 4 when the aggregation/snapshot lands; C5 only needs the earning caps.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "app.reputation")
public class ReputationProperties {

    /**
     * Max net points a single user can earn per day, across all event types.
     */
    private final int dailyCap;

    /**
     * Diminishing-returns factor: the n-th same-type event today is worth raw/(1 + factor·n).
     */
    private final double diminishingFactor;

    /**
     * Binds {@code app.reputation.*}.
     *
     * @param dailyCap          daily net-points cap (default 150)
     * @param diminishingFactor diminishing-returns factor (default 0.4)
     */
    @ConstructorBinding
    public ReputationProperties(@DefaultValue("150") int dailyCap,
                                @DefaultValue("0.4") double diminishingFactor) {
        this.dailyCap = dailyCap;
        this.diminishingFactor = diminishingFactor;
    }
}
