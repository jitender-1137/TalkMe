package com.neo.chat.config;

import com.neo.chat.enums.ConsentType;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Current required version for each user-level consent. Bumping a version (via env)
 * re-prompts every user whose stored acceptance is now stale. Mirrors the
 * {@link WebPushProperties} pattern.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "app.consent")
public class ConsentProperties {

    private final String age18PlusVersion;
    private final String communityGuidelinesVersion;
    private final String flirtLobbyVersion;

    /**
     * Binds {@code app.consent.*}; each version defaults to {@code 2026-07}.
     */
    @ConstructorBinding
    public ConsentProperties(@DefaultValue("2026-07") String age18PlusVersion,
                             @DefaultValue("2026-07") String communityGuidelinesVersion,
                             @DefaultValue("2026-07") String flirtLobbyVersion) {
        this.age18PlusVersion = age18PlusVersion;
        this.communityGuidelinesVersion = communityGuidelinesVersion;
        this.flirtLobbyVersion = flirtLobbyVersion;
    }

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
