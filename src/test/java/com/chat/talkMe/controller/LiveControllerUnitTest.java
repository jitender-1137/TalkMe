package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.LiveTokenResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.FeatureLockedException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.LiveAudioService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link LiveController} (feature LIVE_AUDIO) — the single
 * POST /live/token endpoint that mints a short-lived LiveKit room token for a chat.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link LiveAudioService} and the real
 * {@link GlobalExceptionHandler}, mirroring {@code FlirtModeControllerUnitTest}.
 *
 * <p><b>Scope boundary:</b> the route is {@code @PreAuthorize("@featureGuard.check('LIVE_AUDIO')")},
 * enforced by Spring method-security AOP which is NOT active in standalone MockMvc — the
 * global LIVE_AUDIO gate (default OFF → TM_FEATURE_LOCKED) is an integration concern. Here we
 * verify request/response wiring, the {@code @Valid @RequestBody} bean-validation surface
 * (NotBlank chatUuid), delegation to the service, and the {@link GlobalExceptionHandler}
 * mapping for every failure the service can raise.
 *
 * <p>Success code read from the controller: {@code TM_982} ("Live token issued").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveController (unit)")
class LiveControllerUnitTest {

    private static final String URL = "/live/token";
    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SUCCESS_CODE = "TM_982";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private LiveAudioService liveAudioService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        LiveController controller = new LiveController(liveAudioService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("alice").email("a@e.com").name("Alice")
                .isGuest(false).roles(Set.of(role))
                .build();
        testUser.setId(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Seeds the {@link SecurityContextHolder} with {@code testUser} so {@code @AuthenticationPrincipal} resolves.
     */
    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Renders the single-field request body {@code {"chatUuid":"<value>"}}.
     */
    private static String body(String chatUuid) {
        return "{\"chatUuid\":\"" + chatUuid + "\"}";
    }

    /**
     * Fixture for a minted LiveKit token response.
     */
    private static LiveTokenResponse token() {
        return LiveTokenResponse.builder()
                .token("jwt-token")
                .wsUrl("wss://livekit.example/rtc")
                .room(CHAT_ID)
                .identity("alice")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /live/token — happy path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /live/token (success)")
    class Success {

        @Test
        void shouldReturn200WithMintedTokenAndForwardArgs() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any())).thenReturn(token());

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Live token issued"))
                    .andExpect(jsonPath("$.data.token").value("jwt-token"))
                    .andExpect(jsonPath("$.data.wsUrl").value("wss://livekit.example/rtc"))
                    .andExpect(jsonPath("$.data.room").value(CHAT_ID))
                    .andExpect(jsonPath("$.data.identity").value("alice"));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(liveAudioService).mintToken(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Bean validation (NotBlank chatUuid) — service never reached
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /live/token (bean validation)")
    class Validation {

        @Test
        void shouldReturn400WhenChatUuidBlank() throws Exception {
            authenticate();

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verifyNoInteractions(liveAudioService);
        }

        @Test
        void shouldReturn400WhenChatUuidMissing() throws Exception {
            authenticate();

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verifyNoInteractions(liveAudioService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Service-raised failures → GlobalExceptionHandler mapping
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /live/token (service failures)")
    class ServiceFailures {

        @Test
        void shouldReturn400WhenLiveAudioNotEnabled() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any()))
                    .thenThrow(new BadRequestException("Live audio is not enabled", "TM_980"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_980"));
        }

        @Test
        void shouldReturn400WhenChatIdInvalid() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any()))
                    .thenThrow(new BadRequestException("Invalid chat id", "TM_400"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_981"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_981"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn403WhenFeatureLocked() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any())).thenThrow(new FeatureLockedException());

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        void shouldReturn403WithTm005OnAccessDenied() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any())).thenThrow(new AccessDeniedException("nope"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn400WithTm071OnIllegalArgument() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad arg"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(liveAudioService.mintToken(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Unauthenticated — principal resolves null → NPE before the service
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /live/token (unauthenticated)")
    class Unauthenticated {

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves null → userDetails.getUser()
            // NPEs inside the controller before the service is reached → catch-all 500.
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(CHAT_ID)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(liveAudioService);
        }
    }
}
