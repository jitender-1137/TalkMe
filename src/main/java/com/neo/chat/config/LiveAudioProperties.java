package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Live A/V configuration (feature #Phase-6). Deferred by default: the whole surface stays OFF
 * until {@code app.live-audio.enabled=true} AND a LiveKit key pair + ws URL are supplied — plus
 * the per-user {@code LIVE_AUDIO} entitlement (global flag {@code features.flags.live_audio}).
 *
 * <p>Zero schema change: STOMP continues to carry app state; LiveKit only carries media, and the
 * server's sole job is to mint a short-lived room access token (see {@code LiveAudioService}).
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "app.live-audio")
public class LiveAudioProperties {

    /**
     * Master on/off for the token seam (independent of the per-user feature flag).
     */
    private final boolean enabled;

    /**
     * LiveKit API key ({@code iss} of the minted JWT).
     */
    private final String apiKey;

    /**
     * LiveKit API secret (HMAC-SHA256 signing key for the JWT).
     */
    private final String apiSecret;

    /**
     * Public LiveKit ws(s):// URL the client connects to; returned alongside the token.
     */
    private final String wsUrl;

    /**
     * Minted-token lifetime in seconds.
     */
    private final long tokenTtlSeconds;

    /**
     * Defaults: seam off, no credentials, 1h token lifetime.
     */
    public LiveAudioProperties() {
        this(false, "", "", "", 3600);
    }

    /**
     * Binds {@code app.live-audio.*}.
     *
     * @param enabled         master on/off (default {@code false})
     * @param apiKey          LiveKit API key (default empty)
     * @param apiSecret       LiveKit API secret (default empty)
     * @param wsUrl           public LiveKit ws(s):// URL (default empty)
     * @param tokenTtlSeconds minted-token lifetime in seconds (default {@code 3600})
     */
    @ConstructorBinding
    public LiveAudioProperties(@DefaultValue("false") boolean enabled,
                               @DefaultValue("") String apiKey,
                               @DefaultValue("") String apiSecret,
                               @DefaultValue("") String wsUrl,
                               @DefaultValue("3600") long tokenTtlSeconds) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.wsUrl = wsUrl;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * True only when the seam is switched on AND fully configured.
     */
    public boolean isReady() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && apiSecret != null && !apiSecret.isBlank()
                && wsUrl != null && !wsUrl.isBlank();
    }
}
