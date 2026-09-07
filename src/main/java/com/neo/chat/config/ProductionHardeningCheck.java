package com.neo.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-fast (or loudly warn) when the running configuration looks like a PRODUCTION deployment
 * (public HTTPS {@code ALLOWED_ORIGINS}) but is missing the {@code prod} Spring profile or exposes
 * developer tooling. This defends against accidentally shipping a developer {@code .env} (which may
 * carry {@code SPRING_PROFILES_ACTIVE=dev}, {@code BOOTUI_ENABLED=ON},
 * {@code BOOTUI_ALLOW_NON_LOCALHOST=true}) to a public host, where:
 * <ul>
 *   <li>the dev profile keeps Swagger/BootUI public and disables the HTTP rate limiter, and</li>
 *   <li>BootUI (permitAll, CSRF-exempt) exposes beans/env/mappings/metrics to the internet.</li>
 * </ul>
 *
 * <p>Behavior is controlled by {@code app.security.production-hardening} (default {@code enforce}):
 * {@code enforce} throws on boot (the systemd unit restarts, so the misconfig is obvious and the
 * insecure app never serves traffic); {@code warn} logs an error but continues; {@code off} skips.
 */
@Slf4j
@Component
public class ProductionHardeningCheck implements ApplicationListener<ApplicationReadyEvent> {

    private final Environment env;
    private final String allowedOrigins;
    private final boolean bootuiEnabled;
    private final boolean bootuiNonLocalhost;
    private final String mode;

    public ProductionHardeningCheck(
            Environment env,
            @Value("${app.cors.allowed-origins:}") String allowedOrigins,
            @Value("${bootui.enabled:OFF}") String bootuiEnabled,
            @Value("${bootui.allow-non-localhost:false}") boolean bootuiNonLocalhost,
            @Value("${app.security.production-hardening:warn}") String mode) {
        this.env = env;
        this.allowedOrigins = allowedOrigins;
        this.bootuiEnabled = "ON".equalsIgnoreCase(bootuiEnabled) || "true".equalsIgnoreCase(bootuiEnabled);
        this.bootuiNonLocalhost = bootuiNonLocalhost;
        this.mode = mode == null ? "enforce" : mode.trim().toLowerCase();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if ("off".equals(mode)) {
            return;
        }
        // 'dev' and 'local' are local-development profiles: a developer's .env may legitimately
        // carry the production origins, so this check must never fire under them. It only runs when
        // neither local profile is active (i.e. a real deployment — expected to use 'prod').
        if (env.acceptsProfiles(Profiles.of("dev", "local"))) {
            return;
        }
        boolean prodProfile = env.acceptsProfiles(Profiles.of("prod"));
        boolean looksPublic = allowedOrigins != null
                && allowedOrigins.contains("https://")
                && !allowedOrigins.contains("localhost")
                && !allowedOrigins.contains("127.0.0.1");

        if (!looksPublic) {
            return; // local/dev: nothing to enforce
        }

        StringBuilder problems = new StringBuilder();
        if (!prodProfile) {
            problems.append("\n  - Public origins are configured but the 'prod' Spring profile is NOT active "
                    + "(active=" + String.join(",", env.getActiveProfiles()) + "). "
                    + "Swagger/BootUI stay public and the HTTP rate limiter is disabled outside 'prod'.");
        }
        if (bootuiEnabled && bootuiNonLocalhost) {
            problems.append("\n  - BootUI is ENABLED with allow-non-localhost=true on a public host: it exposes "
                    + "beans/env/mappings/metrics unauthenticated. Set BOOTUI_ENABLED=OFF in production.");
        }

        if (problems.isEmpty()) {
            return;
        }
        String msg = "INSECURE PRODUCTION CONFIGURATION DETECTED:" + problems
                + "\nSet SPRING_PROFILES_ACTIVE=prod and BOOTUI_ENABLED=OFF, or set "
                + "app.security.production-hardening=warn/off to override.";
        if ("warn".equals(mode)) {
            log.error(msg);
        } else {
            throw new IllegalStateException(msg);
        }
    }
}
