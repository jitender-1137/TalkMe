package com.neo.chat.controller;

import com.neo.chat.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link PublicAssetController}.
 *
 * <p>Standalone {@link MockMvc} with the real {@link GlobalExceptionHandler}, matching the
 * repo's {@code *ControllerUnitTest} convention. The controller has no injected collaborator —
 * it streams a classpath {@link Resource} held in a private field — so each test swaps that
 * field via {@link ReflectionTestUtils} to drive the present/absent branches. This also keeps
 * the test independent of whether {@code processResources} copied {@code mail/logo.png} onto
 * the test classpath (these unit tests are sometimes run with {@code -x processResources}); an
 * existing test-classpath resource ({@code application.yml}) stands in for the present case.
 *
 * <p><b>Scope boundary:</b> filter-chain auth / the {@code /api/v1} path prefix are applied by
 * the servlet config, not the controller, so they are out of scope for a controller unit test
 * (the mapping is exercised at its controller-relative path {@code /assets/logo.png}).
 */
@DisplayName("PublicAssetController (unit)")
class PublicAssetControllerUnitTest {

    private static final String URL = "/assets/logo.png";
    /** Any resource guaranteed present on the TEST classpath (content is irrelevant here). */
    private static final String PRESENT_RESOURCE = "application.yml";
    private static final String MISSING_RESOURCE = "mail/__does_not_exist__.png";

    private PublicAssetController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new PublicAssetController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Nested
    @DisplayName("GET /assets/logo.png")
    class GetLogo {

        @Test
        @DisplayName("resource present → 200 image/png with a 30-day public cache and a non-empty body")
        void servesLogoWhenPresent() throws Exception {
            ReflectionTestUtils.setField(controller, "logo", new ClassPathResource(PRESENT_RESOURCE));

            byte[] body = mockMvc.perform(get(URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                    // CacheControl.maxAge(30, DAYS).cachePublic() → "max-age=2592000, public"
                    .andExpect(header().string("Cache-Control", containsString("max-age=2592000")))
                    .andExpect(header().string("Cache-Control", containsString("public")))
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(body).isNotEmpty();
        }

        @Test
        @DisplayName("resource missing → 404 Not Found with no cache header")
        void notFoundWhenResourceAbsent() throws Exception {
            ReflectionTestUtils.setField(controller, "logo", new ClassPathResource(MISSING_RESOURCE));

            mockMvc.perform(get(URL))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("Cache-Control"));
        }
    }

    @Test
    @DisplayName("is wired to the classpath brand logo at mail/logo.png")
    void defaultResourceIsBrandLogo() {
        // Verifies the served asset's wiring without requiring the file on the classpath,
        // so the assertion holds even when run with -x processResources.
        Resource logo = (Resource) ReflectionTestUtils.getField(new PublicAssetController(), "logo");

        assertThat(logo).isInstanceOf(ClassPathResource.class);
        assertThat(((ClassPathResource) logo).getPath()).isEqualTo("mail/logo.png");
    }
}
