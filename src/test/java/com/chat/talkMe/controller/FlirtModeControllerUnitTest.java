package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.FlirtModeResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FlirtModeService;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link FlirtModeController} (feature FLIRT_MODE) — the per-chat
 * mutual "Flirt Mode" toggle on a single 1:1 (PRIVATE) chat.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FlirtModeService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>Every route is annotated {@code @PreAuthorize("@featureGuard.check('FLIRT_MODE')")}, enforced
 *       by Spring's method-security interceptor (AOP) which is NOT active in a standalone MockMvc
 *       setup — the FLIRT_MODE entitlement gate is covered by the integration test. Here we verify the
 *       controller's request/response wiring and delegation to the service, which owns the
 *       PRIVATE-only / membership (IDOR) / deterministic-keying logic.</li>
 *   <li>The three endpoints declare NO {@code @RequestBody}, {@code @RequestParam}, {@code Pageable}
 *       or enum-in-body — only a {@code String} {@code @PathVariable} and the authenticated principal.
 *       There is therefore no bean-validation surface, no missing-param path and no malformed-JSON
 *       path to exercise; a {@link LocalValidatorFactoryBean} is still wired for parity with the repo
 *       template. All input rejection (bad uuid / not-a-member / not-private) is decided inside the
 *       service and driven here by stubbing service exceptions.</li>
 * </ul>
 *
 * <p>Success codes read directly from the controller: {@code getState} emits {@code TM_000} via
 * {@code success(data)}; {@code enable} emits {@code TM_832} ("Flirt mode enabled"); {@code disable}
 * emits {@code TM_833} ("Flirt mode disabled").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtModeController (unit)")
class FlirtModeControllerUnitTest {

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String BASE = "/chats/" + CHAT_ID + "/flirt-mode";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private FlirtModeService flirtModeService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FlirtModeController controller = new FlirtModeController(flirtModeService);

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
        testUser.setId(42L);
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

    /** A viewer-relative flirt-mode state DTO. */
    private static FlirtModeResponse state(boolean myEnabled, boolean otherEnabled, boolean active) {
        return FlirtModeResponse.builder()
                .chatUuid(CHAT_ID)
                .myEnabled(myEnabled)
                .otherEnabled(otherEnabled)
                .active(active)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatUuid}/flirt-mode  -> getState
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats/{chatUuid}/flirt-mode")
    class GetState {

        @Test
        void shouldReturn200WithViewerRelativeStateAndForwardArgs() throws Exception {
            authenticate();
            // Caller opted in, partner has not → not active yet.
            when(flirtModeService.getState(any(), any())).thenReturn(state(true, false, false));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.chatUuid").value(CHAT_ID))
                    .andExpect(jsonPath("$.data.myEnabled").value(true))
                    .andExpect(jsonPath("$.data.otherEnabled").value(false))
                    .andExpect(jsonPath("$.data.active").value(false));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(flirtModeService).getState(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_ID);
            // A read must never mutate consent.
            verify(flirtModeService, never()).enable(any(), any());
            verify(flirtModeService, never()).disable(any(), any());
        }

        @Test
        void shouldReturn200WithAllFalseStateWhenNoRow() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any())).thenReturn(state(false, false, false));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myEnabled").value(false))
                    .andExpect(jsonPath("$.data.otherEnabled").value(false))
                    .andExpect(jsonPath("$.data.active").value(false));
        }

        @Test
        void shouldReturn200WithActiveWhenBothOptedIn() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any())).thenReturn(state(true, true, true));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myEnabled").value(true))
                    .andExpect(jsonPath("$.data.otherEnabled").value(true))
                    .andExpect(jsonPath("$.data.active").value(true));
        }

        @Test
        void shouldForwardRawPathUuidVerbatimToService() throws Exception {
            authenticate();
            // The controller forwards the decoded path segment straight to the service (which owns
            // uuid parsing). A non-uuid segment with no '/', ';' or space round-trips untouched.
            String weird = "not-a-uuid-😀<img>'OR'1=1--";
            when(flirtModeService.getState(any(), any())).thenReturn(state(false, false, false));

            mockMvc.perform(get("/chats/{id}/flirt-mode", weird))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(flirtModeService).getState(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(weird);
        }

        @Test
        void shouldReturn400WhenChatNotPrivate() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any()))
                    .thenThrow(new BadRequestException("Flirt mode is only available on 1:1 chats", "TM_830"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_830"));
        }

        @Test
        void shouldReturn400WhenChatIdInvalid() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any()))
                    .thenThrow(new BadRequestException("Invalid chat id", "TM_400"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_101"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WithTm005OnAccessDenied() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any())).thenThrow(new AccessDeniedException("nope"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn400WithTm071OnIllegalArgument() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad state"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(flirtModeService.getState(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves null → userDetails.getUser()
            // NPEs inside the controller before the service is reached → catch-all 500.
            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(flirtModeService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatUuid}/flirt-mode/enable  -> enable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatUuid}/flirt-mode/enable")
    class Enable {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            // Caller enables; partner already opted in → now active.
            when(flirtModeService.enable(any(), any())).thenReturn(state(true, true, true));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_832"))
                    .andExpect(jsonPath("$.message").value("Flirt mode enabled"))
                    .andExpect(jsonPath("$.data.myEnabled").value(true))
                    .andExpect(jsonPath("$.data.otherEnabled").value(true))
                    .andExpect(jsonPath("$.data.active").value(true));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(flirtModeService).enable(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_ID);
            // Enabling must never itself read or disable.
            verify(flirtModeService, never()).disable(any(), any());
            verify(flirtModeService, never()).getState(any(), any());
        }

        @Test
        void shouldReturn200AndNotActiveWhenOnlyCallerEnabled() throws Exception {
            authenticate();
            when(flirtModeService.enable(any(), any())).thenReturn(state(true, false, false));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myEnabled").value(true))
                    .andExpect(jsonPath("$.data.otherEnabled").value(false))
                    .andExpect(jsonPath("$.data.active").value(false));
        }

        @Test
        void shouldReturn400WhenChatNotPrivate() throws Exception {
            authenticate();
            when(flirtModeService.enable(any(), any()))
                    .thenThrow(new BadRequestException("Flirt mode is only available on 1:1 chats", "TM_830"));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_830"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(flirtModeService.enable(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(flirtModeService.enable(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_101"));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn500WhenOptimisticLockRetryExhausted() throws Exception {
            authenticate();
            // setConsentWithRetry re-throws the last ObjectOptimisticLockingFailureException after
            // MAX_TOGGLE_ATTEMPTS; there is no dedicated handler, so the catch-all maps it to 500.
            when(flirtModeService.enable(any(), any()))
                    .thenThrow(new ObjectOptimisticLockingFailureException("chat_flirt_mode", 1L));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(flirtModeService.enable(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/enable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(flirtModeService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatUuid}/flirt-mode/disable  -> disable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatUuid}/flirt-mode/disable")
    class Disable {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            // Caller opts out → active reverts to false; partner's flag untouched.
            when(flirtModeService.disable(any(), any())).thenReturn(state(false, true, false));

            mockMvc.perform(post(BASE + "/disable"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_833"))
                    .andExpect(jsonPath("$.message").value("Flirt mode disabled"))
                    .andExpect(jsonPath("$.data.myEnabled").value(false))
                    .andExpect(jsonPath("$.data.otherEnabled").value(true))
                    .andExpect(jsonPath("$.data.active").value(false));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(flirtModeService).disable(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_ID);
            // Disabling must never itself read or enable.
            verify(flirtModeService, never()).enable(any(), any());
            verify(flirtModeService, never()).getState(any(), any());
        }

        @Test
        void shouldReturn400WhenChatNotPrivate() throws Exception {
            authenticate();
            when(flirtModeService.disable(any(), any()))
                    .thenThrow(new BadRequestException("Flirt mode is only available on 1:1 chats", "TM_830"));

            mockMvc.perform(post(BASE + "/disable"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_830"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(flirtModeService.disable(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/disable"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(flirtModeService.disable(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/disable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/disable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(flirtModeService);
        }
    }
}
