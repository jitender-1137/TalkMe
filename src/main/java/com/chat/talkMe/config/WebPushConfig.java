package com.chat.talkMe.config;

import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * Builds the singleton {@link PushService} used to sign and send Web Push
 * messages with the configured VAPID keys. Registers BouncyCastle, which the
 * web-push library relies on for the ECDH/HKDF crypto.
 */
@Slf4j
@Configuration
public class WebPushConfig {

    @Bean
    public PushService pushService(WebPushProperties props) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        WebPushProperties.Vapid vapid = props.getVapid();
        // Build a keyless PushService when VAPID keys are absent instead of passing nulls to the
        // 3-arg constructor (which Base64-decodes them and NPEs). This lets the app boot in any
        // keyless environment (tests / dev / CI) — Web Push is best-effort and simply no-ops/logs
        // until keys are configured. Keys are applied only when both are present.
        PushService pushService = new PushService();
        if (vapid.getPublicKey() != null && vapid.getPrivateKey() != null) {
            pushService.setPublicKey(vapid.getPublicKey());
            pushService.setPrivateKey(vapid.getPrivateKey());
            pushService.setSubject(vapid.getSubject());
        } else {
            log.warn("[WebPush] VAPID keys are not configured — Web Push will no-op until set.");
        }
        return pushService;
    }
}
