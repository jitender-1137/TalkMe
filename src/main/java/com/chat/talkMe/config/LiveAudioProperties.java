package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Live A/V configuration (feature #Phase-6). Deferred by default: the whole surface stays OFF
 * until {@code app.live-audio.enabled=true} AND a LiveKit key pair + ws URL are supplied — plus
 * the per-user {@code LIVE_AUDIO} entitlement (global flag {@code features.flags.live_audio}).
 *
 * <p>Zero schema change: STOMP continues to carry app state; LiveKit only carries media, and the
 * server's sole job is to mint a short-lived room access token (see {@code LiveAudioService}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.live-audio")
public class LiveAudioProperties {

    /** Master on/off for the token seam (independent of the per-user feature flag). */
    private boolean enabled = false;

    /** LiveKit API key ({@code iss} of the minted JWT). */
    private String apiKey = "";

    /** LiveKit API secret (HMAC-SHA256 signing key for the JWT). */
    private String apiSecret = "";

    /** Public LiveKit ws(s):// URL the client connects to; returned alongside the token. */
    private String wsUrl = "";

    /** Minted-token lifetime in seconds. */
    private long tokenTtlSeconds = 3600;

    /** True only when the seam is switched on AND fully configured. */
    public boolean isReady() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && apiSecret != null && !apiSecret.isBlank()
                && wsUrl != null && !wsUrl.isBlank();
    }
}
