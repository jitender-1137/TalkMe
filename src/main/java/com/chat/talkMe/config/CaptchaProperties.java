package com.chat.talkMe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Turnstile configuration. The secret key must be provided via env
 * in production (TURNSTILE_SECRET_KEY). Dev defaults to Cloudflare's "always
 * passes" test secret so local development works without a real key.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.captcha")
public class CaptchaProperties {

    /** Master switch — when false, CAPTCHA checks are skipped. */
    private boolean enabled = true;

    /** Cloudflare Turnstile secret key. */
    private String secretKey;
}
