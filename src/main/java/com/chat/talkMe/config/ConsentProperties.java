package com.chat.talkMe.config;

import com.chat.talkMe.enums.ConsentType;
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

    public String requiredVersion(ConsentType type) {
        return switch (type) {
            case AGE_18_PLUS -> age18PlusVersion;
            case COMMUNITY_GUIDELINES -> communityGuidelinesVersion;
            case FLIRT_LOBBY -> flirtLobbyVersion;
        };
    }
}
