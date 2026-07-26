package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable reputation knobs (features #30/#31). The global {@code dailyCap} is the primary
 * time-gate: combined with per-type caps it bounds how fast anyone can climb, making high
 * levels unreachable in days. The leveling curve (k/p), decay, and retention are added in
 * Phase 4 when the aggregation/snapshot lands; C5 only needs the earning caps.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.reputation")
public class ReputationProperties {

    /** Max net points a single user can earn per day, across all event types. */
    private int dailyCap = 150;

    /** Diminishing-returns factor: the n-th same-type event today is worth raw/(1 + factor·n). */
    private double diminishingFactor = 0.4;
}
