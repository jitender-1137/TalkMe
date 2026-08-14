package com.neo.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Content-Security-Policy builder. The headline invariant: adding the ads
 * feature must NOT weaken the platform's hardened CSP when no ad network is configured
 * — with an empty {@code ads.csp-domains} the emitted policy must be BYTE-IDENTICAL to
 * the pre-ads baseline. When domains ARE configured they must be appended only to the
 * script/frame directives (the ones that don't already allow {@code https:}).
 */
@DisplayName("SecurityConfig CSP builder")
class SecurityConfigCspTest {

    /**
     * The exact CSP the app shipped before ads existed — do not edit to match code.
     */
    private static final String BASELINE =
            "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://challenges.cloudflare.com; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: blob: https:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self' https: wss:; " +
                    "frame-src 'self' https://challenges.cloudflare.com; " +
                    "media-src 'self' blob: https:; " +
                    "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    @Nested
    @DisplayName("no ad domains (default)")
    class Baseline {

        @Test
        @DisplayName("empty list → byte-identical to the hardened baseline")
        void emptyListIsBaseline() {
            assertThat(SecurityConfig.buildContentSecurityPolicy(List.of())).isEqualTo(BASELINE);
        }

        @Test
        @DisplayName("null list → byte-identical to the hardened baseline")
        void nullListIsBaseline() {
            assertThat(SecurityConfig.buildContentSecurityPolicy(null)).isEqualTo(BASELINE);
        }

        @Test
        @DisplayName("blank / whitespace-only entries are ignored → still baseline")
        void blankEntriesIgnored() {
            assertThat(SecurityConfig.buildContentSecurityPolicy(List.of("", "   ")))
                    .isEqualTo(BASELINE);
        }
    }

    @Nested
    @DisplayName("with ad domains configured")
    class WithDomains {

        @Test
        @DisplayName("appends domains to script-src and frame-src only")
        void appendsToScriptAndFrame() {
            String csp = SecurityConfig.buildContentSecurityPolicy(
                    List.of("https://pagead2.googlesyndication.com", "https://*.adsterra.com"));

            assertThat(csp).contains(
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://challenges.cloudflare.com "
                            + "https://pagead2.googlesyndication.com https://*.adsterra.com;");
            assertThat(csp).contains(
                    "frame-src 'self' https://challenges.cloudflare.com "
                            + "https://pagead2.googlesyndication.com https://*.adsterra.com;");
            // Untouched directives keep the baseline exactly.
            assertThat(csp).contains("style-src 'self' 'unsafe-inline';");
            assertThat(csp).contains("object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        }

        @Test
        @DisplayName("trims surrounding whitespace on each domain")
        void trimsDomains() {
            String csp = SecurityConfig.buildContentSecurityPolicy(List.of("  https://ads.example.com  "));
            assertThat(csp).contains("https://challenges.cloudflare.com https://ads.example.com;");
        }
    }
}
