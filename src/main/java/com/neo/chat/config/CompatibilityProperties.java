package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable weights for the compatibility engine (feature #10). Each factor contributes
 * {@code weight × factorScore(0..1)}; weights are intended to sum to ~100 so the overall
 * score reads as a percentage. Override per-env via {@code match.compatibility.*}.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "match.compatibility")
public class CompatibilityProperties {
    private final int interests;
    private final int hobbies;
    private final int languages;
    private final int age;
    private final int timezone;
    private final int activity;
    private final int personality;
    private final int energy;
    private final int mood;

    /**
     * Production default weights (total 100) — the values used when no property is set.
     */
    public CompatibilityProperties() {
        this(22, 12, 15, 10, 10, 8, 10, 8, 5);
    }

    /**
     * Binds {@code match.compatibility.*}; every weight falls back to its production default.
     */
    @ConstructorBinding
    public CompatibilityProperties(@DefaultValue("22") int interests,
                                   @DefaultValue("12") int hobbies,
                                   @DefaultValue("15") int languages,
                                   @DefaultValue("10") int age,
                                   @DefaultValue("10") int timezone,
                                   @DefaultValue("8") int activity,
                                   @DefaultValue("10") int personality,
                                   @DefaultValue("8") int energy,
                                   @DefaultValue("5") int mood) {
        this.interests = interests;
        this.hobbies = hobbies;
        this.languages = languages;
        this.age = age;
        this.timezone = timezone;
        this.activity = activity;
        this.personality = personality;
        this.energy = energy;
        this.mood = mood;
    }

    /**
     * Sum of all factor weights (intended to be ~100 so the score reads as a percentage).
     *
     * @return the total of every configured weight.
     */
    public int total() {
        return interests + hobbies + languages + age + timezone + activity + personality + energy + mood;
    }
}
