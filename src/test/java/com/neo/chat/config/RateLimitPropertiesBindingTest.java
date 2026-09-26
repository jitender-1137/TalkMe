package com.neo.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-endpoint override map binds correctly from the flattened property form that YAML's
 * bracket-literal keys produce (e.g. {@code endpoints[GET /api/v1/users/search]}), and that the exact
 * key strings match what {@link com.neo.chat.security.RateLimitingFilter} looks up
 * ({@code METHOD + " " + normalizedRoute}). A binding mismatch would silently drop overrides back to
 * the default — throttling genuine users on the raised endpoints and weakening the tightened ones.
 */
@DisplayName("RateLimitProperties binding")
class RateLimitPropertiesBindingTest {

    private RateLimitProperties bind(Map<String, Object> props) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        // bindOrCreate applies @DefaultValue defaults even when nothing is set under the prefix,
        // mirroring how @ConfigurationPropertiesScan instantiates the bean.
        return new Binder(source)
                .bindOrCreate("app.security.rate-limit", RateLimitProperties.class);
    }

    @Test
    @DisplayName("bracket-literal endpoint keys bind verbatim and resolve via endpointLimit()")
    void endpointOverridesBindWithExactKeys() {
        Map<String, Object> props = new HashMap<>();
        props.put("app.security.rate-limit.enabled", true);
        props.put("app.security.rate-limit.window-seconds", 60);
        props.put("app.security.rate-limit.endpoint-default", 60);
        // Flattened form of the YAML bracket-literal keys ("[GET /api/v1/...]": n).
        props.put("app.security.rate-limit.endpoints[GET /api/v1/users/search]", 200);
        props.put("app.security.rate-limit.endpoints[GET /api/v1/chats/:id/messages]", 200);
        props.put("app.security.rate-limit.endpoints[POST /api/v1/auth/login]", 10);
        props.put("app.security.rate-limit.endpoints[POST /api/v1/friends/requests]", 30);

        RateLimitProperties p = bind(props);

        // Keys match the filter's "METHOD /route" lookup verbatim (case + slashes + :id preserved).
        assertThat(p.endpointLimit("GET /api/v1/users/search")).isEqualTo(200);
        assertThat(p.endpointLimit("GET /api/v1/chats/:id/messages")).isEqualTo(200);
        assertThat(p.endpointLimit("POST /api/v1/auth/login")).isEqualTo(10);
        assertThat(p.endpointLimit("POST /api/v1/friends/requests")).isEqualTo(30);
        // Anything without an override falls back to the default.
        assertThat(p.endpointLimit("GET /api/v1/anything/else")).isEqualTo(60);
    }

    @Test
    @DisplayName("defaults apply when nothing is configured")
    void defaultsWhenUnset() {
        RateLimitProperties p = bind(new HashMap<>());
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getWindowSeconds()).isEqualTo(60);
        assertThat(p.getAuthLimit()).isEqualTo(1200);
        assertThat(p.getAnonLimit()).isEqualTo(120);
        assertThat(p.getEndpointDefault()).isEqualTo(60);
        assertThat(p.endpointLimit("GET /api/v1/whatever")).isEqualTo(60);
    }
}
