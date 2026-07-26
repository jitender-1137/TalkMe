package com.chat.talkMe.config;

import com.chat.talkMe.enums.FeatureKey;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tier-1 global kill-switches for the Late-Night Social features. Backed by
 * {@code features.*} in application.yml (per-env overridable via env vars), so any
 * feature can be dark-launched or emergency-disabled platform-wide without a deploy.
 *
 * A key omitted from {@link #flags} falls back to {@link #enabledByDefault}. Global
 * enablement rolls up through {@link FeatureKey#getParent()} — a child is globally
 * off whenever its parent is globally off.
 *
 * Mirrors the {@link WebPushProperties} pattern.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "features")
public class FeatureFlags {

    /** Default for any feature key not explicitly listed in {@link #flags}. */
    private boolean enabledByDefault = true;

    /** Wire-name → enabled. e.g. {@code flirt_lobby: true}, {@code live_audio: false}. */
    private Map<String, Boolean> flags = new HashMap<>();

    /** True when the feature (and all its ancestors) are globally enabled. */
    public boolean isGloballyEnabled(FeatureKey key) {
        if (key == null) return false;
        boolean self = flags.getOrDefault(key.wireName(), enabledByDefault);
        if (!self) return false;
        return key.getParent() == null || isGloballyEnabled(key.getParent());
    }
}
