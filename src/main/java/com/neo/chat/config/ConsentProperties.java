package com.neo.chat.config;

import com.neo.chat.enums.ConsentType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Current required version for each user-level consent. Bumping a version (via env)
 * re-prompts every user whose stored acceptance is now stale. Mirrors the
 * {@link WebPushProperties} pattern.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.consent")
public class ConsentProperties {

    private String age18PlusVersion = "2026-07";
    private String communityGuidelinesVersion = "2026-07";
    private String flirtLobbyVersion = "2026-07";

    /**
     * The currently-required version string for the given consent type; a user whose stored
     * acceptance differs is re-prompted.
     *
     * @param type the {@link ConsentType} to look up.
     * @return the required version for that consent.
     */
    public String requiredVersion(ConsentType type) {
        return switch (type) {
            case AGE_18_PLUS -> age18PlusVersion;
            case COMMUNITY_GUIDELINES -> communityGuidelinesVersion;
            case FLIRT_LOBBY -> flirtLobbyVersion;
        };
    }
}
