package com.neo.chat.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cloudflare Turnstile configuration. The secret key must be provided via env
 * in production (TURNSTILE_SECRET_KEY). Dev defaults to Cloudflare's "always
 * passes" test secret so local development works without a real key.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "security.captcha")
public class CaptchaProperties {

    /**
     * Master switch — when false, CAPTCHA checks are skipped.
     */
    private final boolean enabled;

    /**
     * Cloudflare Turnstile secret key.
     */
    private final String secretKey;

    /**
     * Binds {@code security.captcha.*}.
     *
     * @param enabled   master switch (default {@code true})
     * @param secretKey Turnstile secret key ({@code null} when unset)
     */
    @ConstructorBinding
    public CaptchaProperties(@DefaultValue("true") boolean enabled, String secretKey) {
        this.enabled = enabled;
        this.secretKey = secretKey;
    }
}
