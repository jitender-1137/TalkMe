package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FeatureAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link FeatureController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FeatureAccessService} and the real
 * {@link GlobalExceptionHandler}. Covers the effective-features read and the self-preference toggle,
 * including the controller-level {@code FeatureKey.fromWire} guard (unknown key → 400/TM_002 — note
 * that this ServiceException deliberately reuses the TM_002 code but with a 400 status).
 *
 * <p><b>Scope boundary:</b> filter-chain auth is out of scope for a controller unit test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureController (unit)")
class FeatureControllerUnitTest {

    private static final String BASE = "/features";
    private static final String WIRE = "night_owl"; // FeatureKey.NIGHT_OWL

    @Mock
    private FeatureAccessService featureAccessService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FeatureController controller = new FeatureController(featureAccessService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("testuser").email("t@e.com").name("Test User")
                .isGuest(false).roles(Set.of(role))
                .build();

        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /features
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /features")
    class GetFeatures {

        @Test
        void shouldReturnEffectiveFeatureSet() throws Exception {
            when(featureAccessService.effectiveWireNames(testUser))
                    .thenReturn(Set.of("night_owl", "mood_energy"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.features").isArray());

            verify(featureAccessService).effectiveWireNames(testUser);
        }

        @Test
        void shouldReturnEmptyFeatureSet() throws Exception {
            when(featureAccessService.effectiveWireNames(any())).thenReturn(Set.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.features").isArray())
                    .andExpect(jsonPath("$.data.features").isEmpty());
        }

        @Test
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(featureAccessService.effectiveWireNames(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /features/{key}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /features/{key}")
    class ToggleFeature {

        @Test
        void shouldEnableFeatureWhenEnabledTrue() throws Exception {
            doNothing().when(featureAccessService).setSelfPreference(any(), any(), eq(true));
            when(featureAccessService.effectiveWireNames(any())).thenReturn(Set.of(WIRE));

            mockMvc.perform(put(BASE + "/" + WIRE).param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_066"))
                    .andExpect(jsonPath("$.data.features").isArray());

            verify(featureAccessService).setSelfPreference(testUser, FeatureKey.NIGHT_OWL, true);
            verify(featureAccessService).effectiveWireNames(testUser);
        }

        @Test
        void shouldDisableFeatureWhenEnabledFalse() throws Exception {
            doNothing().when(featureAccessService).setSelfPreference(any(), any(), eq(false));
            when(featureAccessService.effectiveWireNames(any())).thenReturn(Set.of());

            mockMvc.perform(put(BASE + "/" + WIRE).param("enabled", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_066"));

            verify(featureAccessService).setSelfPreference(testUser, FeatureKey.NIGHT_OWL, false);
        }

        @Test
        void shouldAcceptCaseInsensitiveWireKey() throws Exception {
            doNothing().when(featureAccessService).setSelfPreference(any(), any(), eq(true));
            when(featureAccessService.effectiveWireNames(any())).thenReturn(Set.of(WIRE));

            mockMvc.perform(put(BASE + "/NiGhT_oWl").param("enabled", "true"))
                    .andExpect(status().isOk());

            // fromWire uppercases → resolves to NIGHT_OWL regardless of path casing.
            verify(featureAccessService).setSelfPreference(testUser, FeatureKey.NIGHT_OWL, true);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenKeyUnknown() throws Exception {
            mockMvc.perform(put(BASE + "/not_a_feature").param("enabled", "true"))
                    .andExpect(status().isBadRequest())
                    // Controller throws BadRequestException(..., "TM_002") — 400 status, TM_002 code.
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));

            verifyNoInteractions(featureAccessService);
        }

        @Test
        void shouldReturn500WhenEnabledParamMissing() throws Exception {
            mockMvc.perform(put(BASE + "/" + WIRE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));

            verify(featureAccessService, never()).setSelfPreference(any(), any(), anyBoolean());
        }

        @Test
        void shouldReturn500WhenServiceThrows() throws Exception {
            doNothing().when(featureAccessService).setSelfPreference(any(), any(), eq(true));
            when(featureAccessService.effectiveWireNames(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(put(BASE + "/" + WIRE).param("enabled", "true"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }

    // Local matcher helper to avoid an extra import line at top for a single use.
    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
