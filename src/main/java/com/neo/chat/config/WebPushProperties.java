package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * VAPID / Web Push configuration. Keys are provided via env in production
 * (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY / VAPID_SUBJECT).
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 * The nested {@link Vapid} block is constructor-bound the same way, so {@code webpush.vapid.*}
 * binds as before.
 */
@Getter
@ConfigurationProperties(prefix = "webpush")
public class WebPushProperties {

    /**
     * Master switch — when false, no Web Push is sent (WebSocket-only).
     */
    private final boolean enabled;

    private final Vapid vapid;

    /**
     * Defaults: enabled, with an empty (unconfigured) VAPID block.
     */
    public WebPushProperties() {
        this(true, new Vapid());
    }

    /**
     * Binds {@code webpush.*}.
     *
     * @param enabled master switch (default {@code true})
     * @param vapid   the {@code webpush.vapid.*} block; {@code null} (nothing configured) yields
     *                an empty block so callers never see a null
     */
    @ConstructorBinding
    public WebPushProperties(@DefaultValue("true") boolean enabled, Vapid vapid) {
        this.enabled = enabled;
        this.vapid = vapid != null ? vapid : new Vapid();
    }

    /**
     * The {@code webpush.vapid.*} key material. Immutable — constructor-bound like its
     * enclosing {@link WebPushProperties}.
     */
    @Getter
    public static class Vapid {
        private final String publicKey;
        private final String privateKey;
        /**
         * Contact URI, e.g. "mailto:admin@talkme.app".
         */
        private final String subject;

        /**
         * An empty (unconfigured) block — every value {@code null}, as before configuration.
         */
        public Vapid() {
            this(null, null, null);
        }

        /**
         * Binds {@code webpush.vapid.*}; each value is {@code null} when unset.
         *
         * @param publicKey  VAPID public key
         * @param privateKey VAPID private key
         * @param subject    contact URI, e.g. "mailto:admin@talkme.app"
         */
        @ConstructorBinding
        public Vapid(String publicKey, String privateKey, String subject) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
            this.subject = subject;
        }
    }
}
