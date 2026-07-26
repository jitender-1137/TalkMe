package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.SecretCrushMatchResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.SecretCrushService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link SecretCrushController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link SecretCrushService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>The class- and method-level {@code @PreAuthorize} ({@code hasRole('USER')} and the
 *       {@code @featureGuard.check('SECRET_CRUSH')} entitlement gate) are enforced by Spring's
 *       method-security interceptor, which is inactive in standalone MockMvc — covered by the
 *       integration test.</li>
 *   <li>The controller has NO {@code @RequestBody} and NO {@code @RequestParam}: it only takes a
 *       {@code String} {@code @PathVariable} and the authenticated principal. Consequently there is
 *       no bean-validation surface (no {@code VE_101}/{@code MethodArgumentNotValid} path), no
 *       missing-request-param 500 path, and no malformed-JSON 500 path to exercise here.</li>
 *   <li>The controller forwards the raw path {@code userUuid} straight to the service without a
 *       {@code repo.findByUuid} lookup, so UUID validity / self-crush / not-found are all decided in
 *       the service and are driven here by stubbing service exceptions.</li>
 * </ul>
 *
 * <p>All three endpoints emit success code {@code TM_000} directly from the controller
 * ({@code SuccessResponseDto.success(...)}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecretCrushController (unit)")
class SecretCrushControllerUnitTest {

    private static final String BASE = "/secret-crush";
    private static final String TARGET_ID = "target-uuid-1";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private SecretCrushService secretCrushService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        SecretCrushController controller = new SecretCrushController(secretCrushService);

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

    /** A one-sided crush entry: reveals nothing about reciprocity. */
    private static SecretCrushMatchResponse oneSided(String partnerUuid, String partnerUsername) {
        return SecretCrushMatchResponse.builder()
                .matched(false)
                .partnerUuid(partnerUuid)
                .partnerUsername(partnerUsername)
                .build();
    }

    /** A confirmed mutual match, with partner card + compatibility populated. */
    private static SecretCrushMatchResponse matched() {
        return SecretCrushMatchResponse.builder()
                .matched(true)
                .partnerUuid(TARGET_ID)
                .partnerName("Partner Name")
                .partnerUsername("partner")
                .partnerAvatar("https://cdn/avatar.png")
                .partnerMood("Playful")
                .partnerCountry("IN")
                .compatibility(CompatibilityScore.builder()
                        .overall(87)
                        .bucket("HIGH")
                        .breakdown(Map.of("interests", 90))
                        .highlights(List.of("both night owls"))
                        .explanation("You two vibe.")
                        .build())
                .chatId(null)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /secret-crush/{userUuid}  -> addCrush
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /secret-crush/{userUuid}")
    class AddCrush {

        @Test
        void shouldReturn200AndNonMatchedResponseAndForwardArgs() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any())).thenReturn(oneSided(null, null));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.matched").value(false))
                    // Secrecy invariant: a non-match must not leak any partner identity.
                    .andExpect(jsonPath("$.data.partnerUuid").doesNotExist())
                    .andExpect(jsonPath("$.data.partnerUsername").doesNotExist());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(secretCrushService).addCrush(user.capture(), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET_ID);
            assertThat(user.getValue()).isSameAs(testUser);
            verify(secretCrushService, never()).withdrawCrush(any(), any());
        }

        @Test
        void shouldReturn200AndRevealPartnerOnMutualMatch() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(eq(testUser), eq(TARGET_ID))).thenReturn(matched());

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.matched").value(true))
                    .andExpect(jsonPath("$.data.partnerUuid").value(TARGET_ID))
                    .andExpect(jsonPath("$.data.partnerUsername").value("partner"))
                    .andExpect(jsonPath("$.data.partnerMood").value("Playful"))
                    .andExpect(jsonPath("$.data.partnerCountry").value("IN"))
                    .andExpect(jsonPath("$.data.compatibility.overall").value(87))
                    .andExpect(jsonPath("$.data.compatibility.bucket").value("HIGH"))
                    .andExpect(jsonPath("$.data.chatId").doesNotExist());
        }

        @Test
        void shouldForwardRawPathValueVerbatimIncludingUnicodeAndInjectionText() throws Exception {
            authenticate();
            // The controller does not sanitise or parse the path — it forwards the decoded string
            // straight to the service. Unicode/emoji + XSS/SQLi payloads must pass through untouched.
            String payload = "😀<img>'OR'1=1--"; // no '/', ';' or space so the path segment round-trips
            when(secretCrushService.addCrush(any(), any())).thenReturn(oneSided(null, null));

            mockMvc.perform(post(BASE + "/{uuid}", payload))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(secretCrushService).addCrush(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400WhenCrushingOnSelf() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new BadRequestException("You cannot crush on yourself", "TM_910"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_910"));
        }

        @Test
        void shouldReturn400WhenTargetIsNotCrushable() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new BadRequestException("You cannot crush on this user", "TM_911"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_911"));
        }

        @Test
        void shouldReturn400WhenUserIdInvalid() throws Exception {
            authenticate();
            // Service-level malformed-id rejection (BadRequest), not a raw UUID parse error.
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new BadRequestException("Invalid user id", "TM_913"));

            mockMvc.perform(post(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_913"));
        }

        @Test
        void shouldReturn400AndInvalidUuidCodeWhenRawUuidParseFails() throws Exception {
            authenticate();
            // If the service surfaces a raw java.util.UUID parse failure, GlobalExceptionHandler
            // maps the "Invalid UUID string" IllegalArgumentException to TM_INVALID_UUID.
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

            mockMvc.perform(post(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        void shouldReturn400AndGenericCodeForOtherIllegalArgument() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new IllegalArgumentException("some other bad argument"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn404WhenTargetNotFound() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_404"));

            mockMvc.perform(post(BASE + "/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_404"));
        }

        @Test
        void shouldReturn409WhenAlreadyMatched() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new ConflictException("Already crushing", "TM_909"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_909"));
        }

        @Test
        void shouldReturn403WhenForbidden() throws Exception {
            authenticate();
            // GlobalExceptionHandler maps a ServiceException by its declared status (403 here).
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new ForbiddenException("Crush not permitted", "TM_005"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn429WhenActiveCrushCapReached() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new TooManyRequestsException(
                            "You have reached the maximum number of active crushes", "TM_912"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_912"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(secretCrushService.addCrush(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /secret-crush/{userUuid}  -> withdrawCrush
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /secret-crush/{userUuid}")
    class WithdrawCrush {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            doNothing().when(secretCrushService).withdrawCrush(any(), any());

            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Crush withdrawn"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(secretCrushService).withdrawCrush(user.capture(), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET_ID);
            assertThat(user.getValue()).isSameAs(testUser);
            verify(secretCrushService, never()).addCrush(any(), any());
        }

        @Test
        void shouldReturn404WhenTargetNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("User not found", "TM_404"))
                    .when(secretCrushService).withdrawCrush(any(), any());

            mockMvc.perform(delete(BASE + "/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_404"));
        }

        @Test
        void shouldReturn400WhenUserIdInvalid() throws Exception {
            authenticate();
            doThrow(new BadRequestException("Invalid user id", "TM_913"))
                    .when(secretCrushService).withdrawCrush(any(), any());

            mockMvc.perform(delete(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_913"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(secretCrushService).withdrawCrush(any(), any());

            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /secret-crush/mine  -> listMine
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /secret-crush/mine")
    class ListMine {

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(secretCrushService.listMine(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(secretCrushService).listMine(testUser);
            verify(secretCrushService, never()).addCrush(any(), any());
        }

        @Test
        void shouldReturn200WithMixedCrushesAndMatch() throws Exception {
            authenticate();
            // A one-sided outgoing crush (caller may always see their own target) plus a match.
            when(secretCrushService.listMine(any()))
                    .thenReturn(List.of(oneSided("crush-uuid-2", "someone"), matched()));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].matched").value(false))
                    .andExpect(jsonPath("$.data[0].partnerUsername").value("someone"))
                    .andExpect(jsonPath("$.data[1].matched").value(true))
                    .andExpect(jsonPath("$.data[1].partnerUsername").value("partner"))
                    .andExpect(jsonPath("$.data[1].compatibility.bucket").value("HIGH"));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(secretCrushService).listMine(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(secretCrushService.listMine(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
