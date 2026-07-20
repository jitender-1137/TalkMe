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
        if (vapid.getPublicKey() == null || vapid.getPrivateKey() == null) {
            log.warn("[WebPush] VAPID keys are not configured — Web Push will fail until set.");
        }
        return new PushService(vapid.getPublicKey(), vapid.getPrivateKey(), vapid.getSubject());
    }
}
