package com.neo.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real build-generated {@code avatars-manifest.json} from the classpath and
 * verifies the catalog validates/resolves preset ids. Doubles as an integrity check on
 * the generated backend manifest.
 */
@DisplayName("AvatarCatalog")
class AvatarCatalogTest {

    private AvatarCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new AvatarCatalog(new ObjectMapper());
        catalog.load(); // package-private @PostConstruct
    }

    @Test
    void validatesAndResolvesKnownPresets() {
        // Every collection seeds at least a "-01" entry (custom art or DiceBear fallback).
        assertThat(catalog.isValidId("neutral-01")).isTrue();
        assertThat(catalog.isValidId("female-01")).isTrue();
        assertThat(catalog.resolvePath("female-01")).isEqualTo("/avatars/female/female-01.webp");
        assertThat(catalog.isValidPath("/avatars/female/female-01.webp")).isTrue();
    }

    @Test
    void rejectsUnknownIdsAndPaths() {
        assertThat(catalog.isValidId("nope")).isFalse();
        assertThat(catalog.isValidId(null)).isFalse();
        assertThat(catalog.resolvePath("nope")).isNull();
        assertThat(catalog.isValidPath("/avatars/female/not-real.webp")).isFalse();
        assertThat(catalog.isValidPath(null)).isFalse();
    }
}
