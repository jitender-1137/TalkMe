package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PresenceService;
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

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link PresenceController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link PresenceService} + {@link UserRepository} and
 * the real {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered — every endpoint takes {@code @RequestParam}/{@code @PathVariable} scalars plus
 * {@code @AuthenticationPrincipal}; none has a {@code @RequestBody}, so neither the tolerant
 * Jackson mapper nor a {@code Pageable} resolver is needed.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) and any
 * {@code @PreAuthorize} method-security gates are enforced by Spring's security layer (AOP / filter
 * chain) which is NOT active in a standalone MockMvc setup — those are covered by the integration
 * tests. Here we verify the controller's request/response wiring, its status-enum parsing, the
 * owner-vs-other privacy branching, and delegation to the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceController (unit)")
class PresenceControllerUnitTest {

    private static final String BASE = "/presence";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private PresenceService presenceService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        PresenceController controller = new PresenceController(presenceService, userRepository);

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
        testUser.setId(1L); // needed for the owner-vs-other id comparison in GET /presence/{username}
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

    private static User otherUser(long id, String username) {
        User u = User.builder()
                .username(username).email(username + "@e.com").name("Other").isGuest(false).build();
        u.setId(id);
        return u;
    }

    private static UserPresence presence(String status, boolean ghost, boolean invisible, boolean hideLastSeen) {
        return UserPresence.builder()
                .status(status)
                .ghostModeEnabled(ghost)
                .invisibleModeEnabled(invisible)
                .hideLastSeenEnabled(hideLastSeen)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /presence/status
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /presence/status")
    class SetStatus {

        @Test
        void shouldReturn200AndForwardParsedStatus() throws Exception {
            authenticate();
            doNothing().when(presenceService).setStatus(any(), any());

            mockMvc.perform(put(BASE + "/status").param("status", "ONLINE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_001"))
                    .andExpect(jsonPath("$.message").value("Presence status updated successfully"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<PresenceStatus> status = ArgumentCaptor.forClass(PresenceStatus.class);
            verify(presenceService).setStatus(eq(testUser), status.capture());
            assertThat(status.getValue()).isEqualTo(PresenceStatus.ONLINE);
        }

        @Test
        void shouldUppercaseLowercaseStatusBeforeParsing() throws Exception {
            authenticate();
            doNothing().when(presenceService).setStatus(any(), any());

            mockMvc.perform(put(BASE + "/status").param("status", "away"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_001"));

            ArgumentCaptor<PresenceStatus> status = ArgumentCaptor.forClass(PresenceStatus.class);
            verify(presenceService).setStatus(eq(testUser), status.capture());
            assertThat(status.getValue()).isEqualTo(PresenceStatus.AWAY);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenStatusInvalid() throws Exception {
            authenticate();
            // Controller catches the valueOf IllegalArgumentException and rethrows a BadRequestException
            // (a ServiceException, status 400) with this specific code — asserted directly from the controller.
            mockMvc.perform(put(BASE + "/status").param("status", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_INVALID_STATUS"));
            verifyNoInteractions(presenceService);
        }

        @Test
        void shouldReturn500WhenStatusParamMissing() throws Exception {
            authenticate();
            // Required param absent → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(put(BASE + "/status"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(presenceService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(presenceService).setStatus(any(), any());
            mockMvc.perform(put(BASE + "/status").param("status", "ONLINE"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /presence/ghost
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /presence/ghost")
    class ToggleGhost {

        @Test
        void shouldEnableGhostModeWhenEnabledTrue() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleGhostMode(any(), eq(true));

            mockMvc.perform(put(BASE + "/ghost").param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_002"))
                    .andExpect(jsonPath("$.message").value("Ghost Mode enabled"));

            verify(presenceService).toggleGhostMode(testUser, true);
        }

        @Test
        void shouldDisableGhostModeWhenEnabledFalse() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleGhostMode(any(), eq(false));

            mockMvc.perform(put(BASE + "/ghost").param("enabled", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_002"))
                    .andExpect(jsonPath("$.message").value("Ghost Mode disabled"));

            verify(presenceService).toggleGhostMode(testUser, false);
        }

        @Test
        void shouldReturn500WhenEnabledParamMissing() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE + "/ghost"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(presenceService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(presenceService).toggleGhostMode(any(), eq(true));
            mockMvc.perform(put(BASE + "/ghost").param("enabled", "true"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /presence/invisible
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /presence/invisible")
    class ToggleInvisible {

        @Test
        void shouldEnableInvisibleModeWhenEnabledTrue() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleInvisibleMode(any(), eq(true));

            mockMvc.perform(put(BASE + "/invisible").param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_003"))
                    .andExpect(jsonPath("$.message").value("Invisible Mode enabled"));

            verify(presenceService).toggleInvisibleMode(testUser, true);
        }

        @Test
        void shouldDisableInvisibleModeWhenEnabledFalse() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleInvisibleMode(any(), eq(false));

            mockMvc.perform(put(BASE + "/invisible").param("enabled", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_003"))
                    .andExpect(jsonPath("$.message").value("Invisible Mode disabled"));

            verify(presenceService).toggleInvisibleMode(testUser, false);
        }

        @Test
        void shouldReturn500WhenEnabledParamMissing() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE + "/invisible"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(presenceService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /presence/hide-last-seen
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /presence/hide-last-seen")
    class ToggleHideLastSeen {

        @Test
        void shouldEnableHideLastSeenWhenEnabledTrue() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleHideLastSeen(any(), eq(true));

            mockMvc.perform(put(BASE + "/hide-last-seen").param("enabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_005"))
                    .andExpect(jsonPath("$.message").value("Hide Last Seen enabled"));

            verify(presenceService).toggleHideLastSeen(testUser, true);
        }

        @Test
        void shouldDisableHideLastSeenWhenEnabledFalse() throws Exception {
            authenticate();
            doNothing().when(presenceService).toggleHideLastSeen(any(), eq(false));

            mockMvc.perform(put(BASE + "/hide-last-seen").param("enabled", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_005"))
                    .andExpect(jsonPath("$.message").value("Hide Last Seen disabled"));

            verify(presenceService).toggleHideLastSeen(testUser, false);
        }

        @Test
        void shouldReturn500WhenEnabledParamMissing() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE + "/hide-last-seen"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(presenceService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /presence/reset
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /presence/reset")
    class ResetPresence {

        @Test
        void shouldReturn200AndResetPresence() throws Exception {
            authenticate();
            doNothing().when(presenceService).resetPresence(any());

            mockMvc.perform(delete(BASE + "/reset"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_PRESENCE_004"))
                    .andExpect(jsonPath("$.message").value("Presence properties reset successfully"));

            verify(presenceService).resetPresence(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(presenceService).resetPresence(any());
            mockMvc.perform(delete(BASE + "/reset"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /presence/{username}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /presence/{username}")
    class GetPresence {

        @Test
        void shouldReturnOwnersTrueStatusAndDurableFlagsWhenViewingSelf() throws Exception {
            authenticate();
            Instant lastSeen = Instant.parse("2026-07-22T10:15:30Z");
            // Same user (findByUsername returns testUser, id matches) → owner branch.
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(presenceService.getUserPresence(testUser))
                    .thenReturn(presence("ONLINE", true, false, true));
            when(presenceService.getRawStatus(testUser)).thenReturn(PresenceStatus.INVISIBLE);
            when(presenceService.getLastSeen(testUser)).thenReturn(lastSeen);

            mockMvc.perform(get(BASE + "/testuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.username").value("testuser"))
                    // owner sees the RAW (unmasked) status, not the apparent one
                    .andExpect(jsonPath("$.data.status").value("INVISIBLE"))
                    .andExpect(jsonPath("$.data.lastSeenAt").value(lastSeen.toString()))
                    .andExpect(jsonPath("$.data.ghostModeEnabled").value(true))
                    .andExpect(jsonPath("$.data.invisibleModeEnabled").value(false))
                    .andExpect(jsonPath("$.data.hideLastSeenEnabled").value(true));

            verify(presenceService).getRawStatus(testUser);
            verify(presenceService).getLastSeen(testUser);
            // owner branch must not consult the apparent (masked) accessors
            verify(presenceService, never()).getStatus(any());
            verify(presenceService, never()).getApparentLastSeen(any());
        }

        @Test
        void shouldReturnNullLastSeenForSelfWhenLastSeenAbsent() throws Exception {
            authenticate();
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(presenceService.getUserPresence(testUser))
                    .thenReturn(presence("ONLINE", false, false, false));
            when(presenceService.getRawStatus(testUser)).thenReturn(PresenceStatus.ONLINE);
            when(presenceService.getLastSeen(testUser)).thenReturn(null);

            mockMvc.perform(get(BASE + "/testuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ONLINE"))
                    .andExpect(jsonPath("$.data.lastSeenAt").doesNotExist());
        }

        @Test
        void shouldReturnApparentStatusAndSuppressedFlagsWhenViewingOtherUser() throws Exception {
            authenticate();
            User target = otherUser(2L, "bob");
            Instant apparentLastSeen = Instant.parse("2026-07-21T08:00:00Z");
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(target));
            // target has ghost+invisible on in the durable record, but a non-owner must never see them
            when(presenceService.getUserPresence(target))
                    .thenReturn(presence("ONLINE", true, true, true));
            when(presenceService.getStatus(target)).thenReturn(PresenceStatus.OFFLINE);
            when(presenceService.getApparentLastSeen(target)).thenReturn(apparentLastSeen);

            mockMvc.perform(get(BASE + "/bob"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value("bob"))
                    // apparent (Invisible-masked) status, not the raw one
                    .andExpect(jsonPath("$.data.status").value("OFFLINE"))
                    .andExpect(jsonPath("$.data.lastSeenAt").value(apparentLastSeen.toString()))
                    // privacy flags are forced false for other viewers regardless of the DB record
                    .andExpect(jsonPath("$.data.ghostModeEnabled").value(false))
                    .andExpect(jsonPath("$.data.invisibleModeEnabled").value(false))
                    .andExpect(jsonPath("$.data.hideLastSeenEnabled").value(false));

            verify(presenceService).getStatus(target);
            verify(presenceService).getApparentLastSeen(target);
            verify(presenceService, never()).getRawStatus(any());
            verify(presenceService, never()).getLastSeen(any());
        }

        @Test
        void shouldReturnNullLastSeenForOtherUserWhenApparentLastSeenHidden() throws Exception {
            authenticate();
            User target = otherUser(2L, "bob");
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(target));
            when(presenceService.getUserPresence(target))
                    .thenReturn(presence("OFFLINE", false, false, true));
            when(presenceService.getStatus(target)).thenReturn(PresenceStatus.OFFLINE);
            // Invisible / Hide-last-seen collapses apparent last-seen to null.
            when(presenceService.getApparentLastSeen(target)).thenReturn(null);

            mockMvc.perform(get(BASE + "/bob"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lastSeenAt").doesNotExist());
        }

        @Test
        void shouldReturn404WhenTargetUserNotFound() throws Exception {
            authenticate();
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_USER_NOT_FOUND"));

            verifyNoInteractions(presenceService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            User target = otherUser(2L, "bob");
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(target));
            when(presenceService.getUserPresence(target)).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/bob"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
