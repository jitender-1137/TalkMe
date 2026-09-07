package com.neo.chat.config;

import com.neo.chat.util.ClientIp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the proxy-trust settings used by {@link ClientIp}:
 * <ul>
 *   <li>{@code app.security.trusted-proxy-hops} (default 1) — how many reverse proxies sit in front
 *       of the app. Production runs behind Nginx Proxy Manager = 1. Set 0 when the app is exposed
 *       directly, 2 when a CDN sits in front of the proxy.</li>
 *   <li>{@code app.security.trust-cloudflare-header} (default false) — honour {@code CF-Connecting-IP}.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ClientIpConfig {

    @Value("${app.security.trusted-proxy-hops:1}")
    private int trustedProxyHops;

    @Value("${app.security.trust-cloudflare-header:false}")
    private boolean trustCloudflareHeader;

    @PostConstruct
    void apply() {
        ClientIp.setTrustedProxyHops(trustedProxyHops);
        ClientIp.setTrustCloudflareHeader(trustCloudflareHeader);
        log.info("Client IP resolution: trusted proxy hops={}, trust CF-Connecting-IP={}",
                trustedProxyHops, trustCloudflareHeader);
    }
}
