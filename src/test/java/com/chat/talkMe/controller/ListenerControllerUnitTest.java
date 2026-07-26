package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ListenerShiftResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.FeatureLockedException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ListenerService;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ListenerController} — the "Someone Is Listening"
 * volunteer queue (features #26/#27).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ListenerService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> every route is annotated {@code @PreAuthorize("@featureGuard.check('LISTENER')")},
 * which is enforced by Spring's method-security interceptor (AOP) that is NOT active in a standalone
 * MockMvc setup — the LISTENER feature gate is covered by the integration test. Here we verify the
 * controller's request/response wiring and its delegation to the service, which owns the
 * queueing/matching/authorization logic. None of the four endpoints declare a {@code @RequestBody},
 * {@code @RequestParam}, path variable, {@code Pageable}, or enum-in-body, so this test needs neither
 * a tolerant JSON converter nor a {@code PageableHandlerMethodArgumentResolver}, and there are no
 * malformed-body / missing-param / bean-validation cases to exercise. Free-form String pass-through
 * (unicode/emoji/XSS/SQLi) is therefore asserted on the <i>response</i> DTO string fields rather than
 * on a request body.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListenerController (unit)")
class ListenerControllerUnitTest {

    private static final String BASE = "/listener";
    private static final String SHIFT_ID = "shift-uuid-1";
    private static final String ROOM_UUID = "room-uuid-9";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private ListenerService listenerService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ListenerController controller = new ListenerController(listenerService);

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

    private static ListenerShiftResponse availableShift() {
        return ListenerShiftResponse.builder()
                .id(SHIFT_ID)
                .listenerId("listener-1")
                .listenerName("Alex Listener")
                .listenerUsername("alex")
                .listenerAvatar("https://cdn/av.png")
                .status("AVAILABLE")
                .roomChatUuid(null)
                .peopleHelped(3)
                .startedAt(Instant.parse("2026-07-22T10:00:00Z"))
                .build();
    }

    private static ListenerShiftResponse matchedShift() {
        return ListenerShiftResponse.builder()
                .id(SHIFT_ID)
                .listenerId("listener-1")
                .listenerName("Alex Listener")
                .status("ENGAGED")
                .roomChatUuid(ROOM_UUID)
                .peopleHelped(4)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /listener/available  (goAvailable)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /listener/available")
    class GoAvailable {

        @Test
        void shouldReturn200AndForwardAuthenticatedUser() throws Exception {
            authenticate();
            when(listenerService.goAvailable(any())).thenReturn(availableShift());

            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_990"))
                    .andExpect(jsonPath("$.message").value("You're now available to listen"))
                    .andExpect(jsonPath("$.data.id").value(SHIFT_ID))
                    .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data.listenerId").value("listener-1"))
                    .andExpect(jsonPath("$.data.peopleHelped").value(3))
                    // null while merely available — Jackson keeps the key with a null value
                    .andExpect(jsonPath("$.data.roomChatUuid").doesNotExist());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(listenerService).goAvailable(user.capture());
            assertThat(user.getValue().getUsername()).isEqualTo("testuser");
            assertThat(user.getValue().getId()).isEqualTo(42L);
            // Going on duty must never clock the volunteer off or self-match.
            verify(listenerService, never()).endShift(any());
            verify(listenerService, never()).requestListener(any(), any());
        }

        @Test
        void shouldPassThroughUnicodeEmojiAndInjectionStringsVerbatim() throws Exception {
            authenticate();
            // Free-form listener name carries unicode/emoji + XSS + SQLi; the controller must
            // serialize it verbatim (no escaping/stripping at this layer).
            String hostile = "Ålëx 🎧 <script>alert('xss')</script>'; DROP TABLE shifts;--";
            when(listenerService.goAvailable(any())).thenReturn(
                    ListenerShiftResponse.builder().id(SHIFT_ID).status("AVAILABLE").listenerName(hostile).build());

            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.listenerName").value(hostile));
        }

        @Test
        void shouldReturn409WhenServiceReportsConflict() throws Exception {
            authenticate();
            when(listenerService.goAvailable(any()))
                    .thenThrow(new ConflictException("Already on an engaged shift", "TM_931"));
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_931"));
        }

        @Test
        void shouldReturn400OnIllegalArgument() throws Exception {
            authenticate();
            when(listenerService.goAvailable(any())).thenThrow(new IllegalArgumentException("bad state"));
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn403OnAccessDenied() throws Exception {
            authenticate();
            when(listenerService.goAvailable(any()))
                    .thenThrow(new AccessDeniedException("nope"));
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn403WithFeatureLockedCodeWhenFeatureGuardThrows() throws Exception {
            authenticate();
            // Mirrors a defensive service-side re-check of the LISTENER feature gate.
            when(listenerService.goAvailable(any())).thenThrow(new FeatureLockedException());
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(listenerService.goAvailable(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves null → userDetails.getUser()
            // NPEs inside the controller before the service is reached → catch-all 500.
            mockMvc.perform(post(BASE + "/available"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(listenerService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /listener/end  (endShift)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /listener/end")
    class EndShift {

        @Test
        void shouldReturn200WithNullDataAndForwardUser() throws Exception {
            authenticate();
            doNothing().when(listenerService).endShift(any());

            mockMvc.perform(post(BASE + "/end"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_991"))
                    .andExpect(jsonPath("$.message").value("Listening shift ended"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(listenerService).endShift(user.capture());
            assertThat(user.getValue().getUsername()).isEqualTo("testuser");
            // Clocking off must never re-arm availability.
            verify(listenerService, never()).goAvailable(any());
        }

        @Test
        void shouldReturn404WhenNoActiveShift() throws Exception {
            authenticate();
            doThrow(new NotFoundException("No active shift", "TM_932"))
                    .when(listenerService).endShift(any());
            mockMvc.perform(post(BASE + "/end"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_932"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(listenerService).endShift(any());
            mockMvc.perform(post(BASE + "/end"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/end"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(listenerService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /listener/request  (requestListener)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /listener/request")
    class RequestListener {

        @Test
        void shouldReturn200WithMatchedRoomAndForwardRequester() throws Exception {
            authenticate();
            when(listenerService.requestListener(any(), any())).thenReturn(matchedShift());

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_992"))
                    .andExpect(jsonPath("$.message").value("Connected you with a listener"))
                    .andExpect(jsonPath("$.data.status").value("ENGAGED"))
                    .andExpect(jsonPath("$.data.roomChatUuid").value(ROOM_UUID))
                    .andExpect(jsonPath("$.data.listenerId").value("listener-1"));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(listenerService).requestListener(user.capture(), any());
            assertThat(user.getValue().getUsername()).isEqualTo("testuser");
            // Requesting a listener must never itself clock the requester on/off duty.
            verify(listenerService, never()).goAvailable(any());
            verify(listenerService, never()).endShift(any());
        }

        @Test
        void shouldReturn404WhenNoListenerAvailable() throws Exception {
            authenticate();
            when(listenerService.requestListener(any(), any()))
                    .thenThrow(new NotFoundException("No listeners available right now", "TM_933"));
            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_933"));
        }

        @Test
        void shouldReturn409WhenRequesterAlreadyEngaged() throws Exception {
            authenticate();
            when(listenerService.requestListener(any(), any()))
                    .thenThrow(new ConflictException("Already in a listening room", "TM_934"));
            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_934"));
        }

        @Test
        void shouldReturn403WhenRequesterForbidden() throws Exception {
            authenticate();
            when(listenerService.requestListener(any(), any()))
                    .thenThrow(new ForbiddenException("Cannot request as a listener on duty", "TM_935"));
            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_935"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(listenerService.requestListener(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(listenerService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /listener/available  (listAvailable)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /listener/available")
    class ListAvailable {

        @Test
        void shouldReturn200WithQueueAndDefaultSuccessCode() throws Exception {
            authenticate();
            when(listenerService.listAvailable())
                    .thenReturn(List.of(availableShift(), matchedShift()));

            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // controller uses the single-arg SuccessResponseDto.success(data) → generic code
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(SHIFT_ID))
                    .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data[1].roomChatUuid").value(ROOM_UUID));

            // listAvailable takes no user argument — it's the public live queue.
            verify(listenerService).listAvailable();
            verify(listenerService, never()).goAvailable(any());
            verify(listenerService, never()).requestListener(any(), any());
        }

        @Test
        void shouldReturn200WithEmptyQueue() throws Exception {
            authenticate();
            when(listenerService.listAvailable()).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(listenerService).listAvailable();
        }

        @Test
        void shouldReturn400WhenServiceRejectsRequest() throws Exception {
            authenticate();
            when(listenerService.listAvailable())
                    .thenThrow(new BadRequestException("Bad queue query", "TM_936"));
            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_936"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(listenerService.listAvailable()).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
