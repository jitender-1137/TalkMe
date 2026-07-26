package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FlirtLobbyService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link FlirtLobbyController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FlirtLobbyService} and the real
 * {@link GlobalExceptionHandler}. All three routes carry no request body, no query params
 * and no path variables — the only wiring to verify is that the authenticated
 * {@link CustomUserDetails#getUser()} principal reaches the service, that the service's
 * {@code List<NightUserCard>} / {@code void} results are wrapped in the standard envelope
 * with the exact {@code messageCode} the controller emits, and that thrown exceptions map
 * through the handler to the right HTTP status.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('FLIRT_LOBBY')")}
 * gate on {@code enter}/{@code online} and the {@code @PreAuthorize("hasRole('USER')")} gate
 * on {@code leave} are Spring method-security (AOP) which is NOT active in a standalone
 * MockMvc setup — those gates are covered by the integration test. In particular the
 * deliberate design note that {@code leave} is NOT feature-gated (a stranded-user cleanup
 * path) is a method-security concern and cannot be asserted here; this test only proves the
 * controller wiring/delegation.
 *
 * <p>No tolerant Jackson converter and no {@code PageableHandlerMethodArgumentResolver} are
 * registered: no endpoint has a {@code @RequestBody} (so no unboxed-primitive concern), and
 * none takes a {@code Pageable}. There are likewise no {@code @Valid} DTOs, no
 * {@code @RequestParam}s and no enum bindings, so validation / missing-param / malformed-JSON
 * / invalid-enum and free-form-body (unicode/XSS/SQLi) cases are structurally not reachable
 * on this controller and are intentionally omitted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtLobbyController (unit)")
class FlirtLobbyControllerUnitTest {

    private static final String BASE = "/flirt-lobby";
    private static final String OK_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private FlirtLobbyService flirtLobbyService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FlirtLobbyController controller = new FlirtLobbyController(flirtLobbyService);

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

    private static NightUserCard card(String id, String name) {
        return NightUserCard.builder()
                .id(id).name(name).username(name.toLowerCase())
                .avatar("/media/" + id + ".png").mood("PLAYFUL")
                .country("US").presence("ONLINE")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /flirt-lobby/enter
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /flirt-lobby/enter")
    class Enter {

        @Test
        void shouldReturn200AndForwardAuthenticatedUser() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any())).thenReturn(List.of(card("u-1", "Alice"), card("u-2", "Bob")));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value("u-1"))
                    .andExpect(jsonPath("$.data[0].name").value("Alice"))
                    .andExpect(jsonPath("$.data[0].username").value("alice"))
                    .andExpect(jsonPath("$.data[0].mood").value("PLAYFUL"))
                    .andExpect(jsonPath("$.data[0].presence").value("ONLINE"))
                    .andExpect(jsonPath("$.data[1].id").value("u-2"));

            // Captor proves the authenticated principal's User reaches the service,
            // and that entering never triggers a leave or roster read.
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(flirtLobbyService).enter(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            verify(flirtLobbyService, never()).leave(any());
            verify(flirtLobbyService, never()).roster(any());
        }

        @Test
        void shouldReturn200WithEmptyRoster() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any())).thenReturn(List.of());

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(flirtLobbyService).enter(testUser);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new NotFoundException("User not found", "TM_024"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new ForbiddenException("Flirt consent revoked", "TM_FEATURE_LOCKED"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new ServiceException(409, "Already in lobby", "TM_009"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_009"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new ServiceException(400, "Bad lobby state", "TM_400"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new IllegalArgumentException("bad arg"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsAccessDenied() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(flirtLobbyService.enter(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500WhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves to null → NPE on
            // userDetails.getUser() → catch-all 500. (In production the @PreAuthorize gate,
            // inactive in standalone MockMvc, rejects this before the body runs.)
            mockMvc.perform(post(BASE + "/enter"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verify(flirtLobbyService, never()).enter(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /flirt-lobby/online  (roster)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /flirt-lobby/online")
    class Online {

        @Test
        void shouldReturn200WithRoster() throws Exception {
            authenticate();
            when(flirtLobbyService.roster(any())).thenReturn(List.of(card("u-9", "Zoe")));

            mockMvc.perform(get(BASE + "/online"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value("u-9"))
                    .andExpect(jsonPath("$.data[0].name").value("Zoe"))
                    .andExpect(jsonPath("$.data[0].presence").value("ONLINE"));

            // roster() receives the viewer (the authenticated user), and reading the
            // roster never enters or leaves the lobby.
            ArgumentCaptor<User> viewer = ArgumentCaptor.forClass(User.class);
            verify(flirtLobbyService).roster(viewer.capture());
            assertThat(viewer.getValue()).isSameAs(testUser);
            verify(flirtLobbyService, never()).enter(any());
            verify(flirtLobbyService, never()).leave(any());
        }

        @Test
        void shouldReturn200WithEmptyRoster() throws Exception {
            authenticate();
            when(flirtLobbyService.roster(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/online"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(flirtLobbyService).roster(testUser);
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(flirtLobbyService.roster(any()))
                    .thenThrow(new ForbiddenException("Not entitled", "TM_FEATURE_LOCKED"));

            mockMvc.perform(get(BASE + "/online"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(flirtLobbyService.roster(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/online"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500WhenUnauthenticated() throws Exception {
            mockMvc.perform(get(BASE + "/online"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verify(flirtLobbyService, never()).roster(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /flirt-lobby/leave
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /flirt-lobby/leave")
    class Leave {

        @Test
        void shouldReturn200AndForwardAuthenticatedUser() throws Exception {
            authenticate();
            doNothing().when(flirtLobbyService).leave(any());

            mockMvc.perform(post(BASE + "/leave"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.message").value("Left flirt lobby"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // Captor proves the authenticated principal reaches leave(), and that leaving
            // never enters the lobby or reads the roster.
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(flirtLobbyService).leave(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            verify(flirtLobbyService, never()).enter(any());
            verify(flirtLobbyService, never()).roster(any());
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Lobby membership not found", "TM_024"))
                    .when(flirtLobbyService).leave(any());

            mockMvc.perform(post(BASE + "/leave"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(flirtLobbyService).leave(any());

            mockMvc.perform(post(BASE + "/leave"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500WhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/leave"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verify(flirtLobbyService, never()).leave(any());
        }
    }
}
