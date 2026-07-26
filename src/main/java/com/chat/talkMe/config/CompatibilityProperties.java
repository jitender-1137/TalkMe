package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable weights for the compatibility engine (feature #10). Each factor contributes
 * {@code weight × factorScore(0..1)}; weights are intended to sum to ~100 so the overall
 * score reads as a percentage. Override per-env via {@code match.compatibility.*}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "match.compatibility")
public class CompatibilityProperties {
    private int interests = 22;
    private int hobbies = 12;
    private int languages = 15;
    private int age = 10;
    private int timezone = 10;
    private int activity = 8;
    private int personality = 10;
    private int energy = 8;
    private int mood = 5;

    public int total() {
        return interests + hobbies + languages + age + timezone + activity + personality + energy + mood;
    }
}
