package com.neo.chat.config;

import com.neo.chat.enums.FeatureKey;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Tier-1 global kill-switches for the Late-Night Social features. Backed by
 * {@code features.*} in application.yml (per-env overridable via env vars), so any
 * feature can be dark-launched or emergency-disabled platform-wide without a deployment.
 * <p>
 * A key omitted from {@link #flags} falls back to {@link #enabledByDefault}. Global
 * enablement rolls up through {@link FeatureKey#getParent()} — a child is globally
 * off whenever its parent is globally off.
 * <p>
 * Mirrors the {@link WebPushProperties} pattern: immutable, bound once at startup through
 * constructor binding (registered by {@code @ConfigurationPropertiesScan} on
 * {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "features")
public class FeatureFlags {

    /**
     * Default for any feature key not explicitly listed in {@link #flags}.
     */
    private final boolean enabledByDefault;

    /**
     * Wire-name → enabled. e.g. {@code flirt_lobby: true}, {@code live_audio: false}.
     */
    private final Map<String, Boolean> flags;

    /**
     * When true, {@link FeatureKey#FLIRT_MODE} skips the email-verified requirement, so
     * users who have NOT verified their email can still use Flirt Mode. The 18+
     * age-verification gate is deliberately NOT relaxed (adults-only stays enforced).
     * Default false → current behavior (email verification required). Bound from
     * {@code features.allow-non-verified-flirt-mode}.
     */
    private final boolean allowNonVerifiedFlirtMode;

    /**
     * Global email-verification gate. When {@code false} (the DEFAULT), the per-feature
     * {@code requiresVerified} gate is relaxed platform-wide, so EVERY user — verified or
     * unverified — can use all features. When {@code true}, the {@code requiresVerified}
     * gate is enforced and only email-verified users can use features that require it.
     * <p>
     * The 18+ age-verification gate ({@code requiresAgeVerified}) is independent and is
     * NOT affected by this flag. Bound from {@code features.require-verified}.
     */
    private final boolean requireVerified;

    /**
     * Defaults: everything enabled-by-default, no explicit per-key overrides, both
     * verification relaxations off.
     */
    public FeatureFlags() {
        this(true, new HashMap<>(), false, false);
    }

    /**
     * Binds {@code features.*}.
     *
     * @param enabledByDefault          fallback for keys absent from {@code flags} (default {@code true})
     * @param flags                     wire-name → enabled overrides; {@code null} (nothing configured)
     *                                  yields an empty map so lookups never see a null
     * @param allowNonVerifiedFlirtMode relax the email-verified gate for Flirt Mode (default {@code false})
     * @param requireVerified           enforce the global email-verification gate (default {@code false})
     */
    @ConstructorBinding
    public FeatureFlags(@DefaultValue("true") boolean enabledByDefault,
                        Map<String, Boolean> flags,
                        @DefaultValue("false") boolean allowNonVerifiedFlirtMode,
                        @DefaultValue("false") boolean requireVerified) {
        this.enabledByDefault = enabledByDefault;
        this.flags = flags != null ? flags : new HashMap<>();
        this.allowNonVerifiedFlirtMode = allowNonVerifiedFlirtMode;
        this.requireVerified = requireVerified;
    }

    /**
     * True when the feature (and all its ancestors) are globally enabled.
     */
    public boolean isGloballyEnabled(FeatureKey key) {
        if (key == null) return false;
        boolean self = flags.getOrDefault(key.wireName(), enabledByDefault);
        if (!self) return false;
        return key.getParent() == null || isGloballyEnabled(key.getParent());
    }
}
