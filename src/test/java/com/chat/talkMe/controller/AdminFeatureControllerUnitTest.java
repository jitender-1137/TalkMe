package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.GrantDecision;
import com.chat.talkMe.enums.GrantScope;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FeatureAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link AdminFeatureController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FeatureAccessService} +
 * {@link UserRepository} and the real {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> this is an ADMIN controller — {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}
 * plus the {@code /api/v1/admin/**} filter-chain rule (JWT auth + {@code ROLE_SUPER_ADMIN}/
 * {@code ROLE_ADMIN} gating) are enforced by Spring's security layer (filter chain + method-security
 * AOP interceptor), neither of which is active in a standalone MockMvc setup. Those role gates are
 * covered by the integration tests. Here we verify the controller's request/response wiring, its
 * path/body parsing, the {@code FeatureKey.fromWire} unknown-feature branch, UUID parsing, and its
 * delegation to the service.
 *
 * <p>No tolerant Jackson mapper is registered: {@link com.chat.talkMe.dto.request.FeatureGrantRequest}
 * has no unboxed primitive fields (only enums with defaults, Strings and an Instant). No Pageable
 * resolver either — no endpoint takes a {@code Pageable}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFeatureController (unit)")
class AdminFeatureControllerUnitTest {

    private static final String BASE = "/admin/features";
    private static final String UUID_STR = "11111111-1111-1111-1111-111111111111";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String INVALID_UUID_CODE = "TM_INVALID_UUID";
    // The controller throws BadRequestException("Unknown feature...", "TM_002") for an unknown key.
    private static final String UNKNOWN_FEATURE_CODE = "TM_002";

    @Mock
    private FeatureAccessService featureAccessService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private User testUser;
    private User targetUser;

    @BeforeEach
    void setUp() {
        AdminFeatureController controller =
                new AdminFeatureController(featureAccessService, userRepository);

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
        targetUser = User.builder()
                .username("target").email("target@e.com").name("Target User")
                .isGuest(false).roles(Set.of(role))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void stubUserFound() {
        when(userRepository.findByUuid(eq(UUID.fromString(UUID_STR)))).thenReturn(Optional.of(targetUser));
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /admin/features/users/{uuid}  (grant)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /users/{uuid} (grant)")
    class Grant {

        @Test
        void shouldReturn200AndForwardAllArgumentsWhenValid() throws Exception {
            authenticate();
            stubUserFound();
            doNothing().when(featureAccessService)
                    .grant(any(), any(), any(), any(), any(), any(), any());

            String body = """
                    {"key":"night_owl","decision":"DENY","scope":"COHORT",\
                    "cohort":"beta","expiresAt":"2030-01-01T00:00:00Z","note":"testing"}""";
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Feature grant applied"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<FeatureKey> key = ArgumentCaptor.forClass(FeatureKey.class);
            ArgumentCaptor<GrantDecision> decision = ArgumentCaptor.forClass(GrantDecision.class);
            ArgumentCaptor<GrantScope> scope = ArgumentCaptor.forClass(GrantScope.class);
            ArgumentCaptor<String> cohort = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(featureAccessService).grant(eq(targetUser), key.capture(), decision.capture(),
                    scope.capture(), cohort.capture(), expiresAt.capture(), note.capture());
            assertThat(key.getValue()).isEqualTo(FeatureKey.NIGHT_OWL);
            assertThat(decision.getValue()).isEqualTo(GrantDecision.DENY);
            assertThat(scope.getValue()).isEqualTo(GrantScope.COHORT);
            assertThat(cohort.getValue()).isEqualTo("beta");
            assertThat(expiresAt.getValue()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
            assertThat(note.getValue()).isEqualTo("testing");
        }

        @Test
        void shouldDefaultDecisionAndScopeWhenOmitted() throws Exception {
            authenticate();
            stubUserFound();
            doNothing().when(featureAccessService)
                    .grant(any(), any(), any(), any(), any(), any(), any());

            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"flirt_lobby\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"));

            ArgumentCaptor<GrantDecision> decision = ArgumentCaptor.forClass(GrantDecision.class);
            ArgumentCaptor<GrantScope> scope = ArgumentCaptor.forClass(GrantScope.class);
            verify(featureAccessService).grant(eq(targetUser), eq(FeatureKey.FLIRT_LOBBY),
                    decision.capture(), scope.capture(), any(), any(), any());
            assertThat(decision.getValue()).isEqualTo(GrantDecision.ALLOW);
            assertThat(scope.getValue()).isEqualTo(GrantScope.ADMIN);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenKeyBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(featureAccessService);
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturn400WhenKeyMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(featureAccessService);
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturn400WhenFeatureUnknown() throws Exception {
            authenticate();
            stubUserFound();
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"not_a_real_feature\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(UNKNOWN_FEATURE_CODE));
            verify(featureAccessService, never())
                    .grant(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldReturn400WhenUuidMalformed() throws Exception {
            authenticate();
            // UUID.fromString throws IllegalArgumentException("Invalid UUID string: ...")
            // → GlobalExceptionHandler maps it to TM_INVALID_UUID / 400.
            mockMvc.perform(post(BASE + "/users/not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"night_owl\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
            verifyNoInteractions(featureAccessService);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"night_owl\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
            verifyNoInteractions(featureAccessService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unreadable JSON has no dedicated handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(featureAccessService);
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            stubUserFound();
            doThrow(new RuntimeException("boom")).when(featureAccessService)
                    .grant(any(), any(), any(), any(), any(), any(), any());
            mockMvc.perform(post(BASE + "/users/" + UUID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"night_owl\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /admin/features/users/{uuid}/{key}  (revoke)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /users/{uuid}/{key} (revoke)")
    class Revoke {

        @Test
        void shouldReturn200AndForwardArgumentsWhenValid() throws Exception {
            authenticate();
            stubUserFound();
            doNothing().when(featureAccessService).revoke(any(), any());

            mockMvc.perform(delete(BASE + "/users/" + UUID_STR + "/night_owl"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Feature grant removed"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<FeatureKey> key = ArgumentCaptor.forClass(FeatureKey.class);
            verify(featureAccessService).revoke(eq(targetUser), key.capture());
            assertThat(key.getValue()).isEqualTo(FeatureKey.NIGHT_OWL);
        }

        @Test
        void shouldReturn400WhenFeatureUnknown() throws Exception {
            authenticate();
            stubUserFound();
            mockMvc.perform(delete(BASE + "/users/" + UUID_STR + "/not_a_real_feature"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(UNKNOWN_FEATURE_CODE));
            verify(featureAccessService, never()).revoke(any(), any());
        }

        @Test
        void shouldReturn400WhenUuidMalformed() throws Exception {
            authenticate();
            mockMvc.perform(delete(BASE + "/users/not-a-uuid/night_owl"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
            verifyNoInteractions(featureAccessService);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());
            mockMvc.perform(delete(BASE + "/users/" + UUID_STR + "/night_owl"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
            verifyNoInteractions(featureAccessService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            stubUserFound();
            doThrow(new RuntimeException("boom")).when(featureAccessService).revoke(any(), any());
            mockMvc.perform(delete(BASE + "/users/" + UUID_STR + "/night_owl"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
