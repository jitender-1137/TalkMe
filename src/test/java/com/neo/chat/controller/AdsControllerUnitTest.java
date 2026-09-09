package com.neo.chat.controller;

import com.neo.chat.config.AdsProperties;
import com.neo.chat.config.FeatureFlags;
import com.neo.chat.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link AdsController}.
 *
 * <p>Standalone {@link MockMvc} with real {@link AdsProperties} / {@link FeatureFlags}
 * (plain config POJOs) and the real {@link GlobalExceptionHandler}. Verifies that
 * {@code GET /ads/config} serves the customisation config and that its {@code enabled}
 * flag tracks the single master kill-switch ({@code features.flags.ads}).
 *
 * <p><b>Scope boundary:</b> filter-chain auth is out of scope for a controller unit test.
 */
@DisplayName("AdsController (unit)")
class AdsControllerUnitTest {

    private static final String URL = "/ads/config";

    private AdsProperties adsProperties;
    private FeatureFlags featureFlags;

    /**
     * Builds a standalone MockMvc for an {@link AdsController} wired with fresh config POJOs, with the
     * master ads kill-switch ({@code features.flags.ads}) set to {@code adsGloballyOn} and a fixed
     * provider/label so assertions stay independent of config defaults.
     */
    private MockMvc mockMvcFor(boolean adsGloballyOn) {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("ads", adsGloballyOn);
        featureFlags = new FeatureFlags(false, flags, false, false);

        // explicit provider/label so the test is default-independent
        adsProperties = new AdsProperties().toBuilder()
                .provider("adsterra")
                .label("Sponsored")
                .build();

        AdsController controller = new AdsController(adsProperties, featureFlags);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @BeforeEach
    void setUp() {
        // each test builds its own MockMvc with the desired global-flag state
    }

    @Nested
    @DisplayName("GET /ads/config")
    class GetConfig {

        @Test
        @DisplayName("ads globally ON → enabled=true with full customisation payload")
        void enabledWhenGloballyOn() throws Exception {
            mockMvcFor(true).perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.enabled").value(true))
                    .andExpect(jsonPath("$.data.provider").value("adsterra"))
                    .andExpect(jsonPath("$.data.label").value("Sponsored"))
                    .andExpect(jsonPath("$.data.frequencyCapPerSession").value(30))
                    .andExpect(jsonPath("$.data.placements.feed.everyN").value(6))
                    .andExpect(jsonPath("$.data.placements.feed.enabled").value(true))
                    .andExpect(jsonPath("$.data.placements.stories.enabled").value(false));
        }

        @Test
        @DisplayName("ads globally OFF → still serves config but enabled=false")
        void disabledWhenGloballyOff() throws Exception {
            mockMvcFor(false).perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.provider").value("adsterra"));
        }

        @Test
        @DisplayName("field names are camelCase on the wire (client contract)")
        void serializesCamelCaseFields() throws Exception {
            adsProperties = new AdsProperties().toBuilder()
                    .adChoicesUrl("https://example.com/why")
                    .build();
            featureFlags = new FeatureFlags(true, Map.of("ads", true), false, false);
            MockMvc mvc = MockMvcBuilders
                    .standaloneSetup(new AdsController(adsProperties, featureFlags))
                    .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                    .build();

            mvc.perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.adChoicesUrl").value("https://example.com/why"))
                    .andExpect(jsonPath("$.data.placements.explore.maxPerSession").value(12));
        }
    }
}
