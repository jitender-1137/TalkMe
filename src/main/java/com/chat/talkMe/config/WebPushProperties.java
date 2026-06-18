package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VAPID / Web Push configuration. Keys are provided via env in production
 * (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY / VAPID_SUBJECT).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "webpush")
public class WebPushProperties {

    /** Master switch — when false, no Web Push is sent (WebSocket-only). */
    private boolean enabled = true;

    private final Vapid vapid = new Vapid();

    @Getter
    @Setter
    public static class Vapid {
        private String publicKey;
        private String privateKey;
        /** Contact URI, e.g. "mailto:admin@talkme.app". */
        private String subject;
    }
}
