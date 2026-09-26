package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * HTTP rate-limit tuning ({@code app.security.rate-limit.*}), applied by
 * {@link com.neo.chat.security.RateLimitingFilter} in prod-like profiles.
 *
 * <p>Two independent fixed-window buckets are enforced per request:
 * <ul>
 *   <li><b>Global</b> — one bucket per authenticated user ({@link #authLimit}) or per client IP
 *       ({@link #anonLimit}) across ALL endpoints. A coarse abuse backstop.</li>
 *   <li><b>Per-endpoint</b> — a separate bucket keyed by the SAME subject PLUS the normalized
 *       {@code METHOD /route} ({@link #endpointDefault}, overridable via {@link #endpoints}), so a
 *       burst on one endpoint (e.g. bulk actions) can't drain the user's whole quota and each
 *       endpoint can be tuned independently.</li>
 * </ul>
 *
 * <p>Immutable, constructor-bound (registered by {@code @ConfigurationPropertiesScan}).
 */
@Getter
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

    /** Master switch. When false the filter passes everything through. */
    private final boolean enabled;
    /** Fixed-window length in seconds (shared by both buckets). */
    private final int windowSeconds;
    /** Global cap per authenticated user, per window, across all endpoints. */
    private final int authLimit;
    /** Global cap per client IP (anonymous), per window, across all endpoints. */
    private final int anonLimit;
    /** Default per-endpoint cap (per subject, per window) when no override matches. */
    private final int endpointDefault;
    /**
     * Per-endpoint overrides, keyed by the normalized {@code "METHOD /route"} (path IDs masked to
     * {@code :id}). In YAML the key needs bracket-literal form, e.g.
     * {@code "[POST /api/v1/friends/requests]": 20}.
     */
    private final Map<String, Integer> endpoints;

    public RateLimitProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60") int windowSeconds,
            @DefaultValue("1200") int authLimit,
            @DefaultValue("120") int anonLimit,
            @DefaultValue("60") int endpointDefault,
            @DefaultValue Map<String, Integer> endpoints) {
        this.enabled = enabled;
        this.windowSeconds = windowSeconds > 0 ? windowSeconds : 60;
        this.authLimit = authLimit > 0 ? authLimit : 1200;
        this.anonLimit = anonLimit > 0 ? anonLimit : 120;
        this.endpointDefault = endpointDefault > 0 ? endpointDefault : 60;
        this.endpoints = endpoints == null ? Map.of() : endpoints;
    }

    /** Resolve the per-endpoint limit for a normalized {@code "METHOD /route"} key. */
    public int endpointLimit(String methodRoute) {
        Integer v = endpoints.get(methodRoute);
        return v != null && v > 0 ? v : endpointDefault;
    }
}
