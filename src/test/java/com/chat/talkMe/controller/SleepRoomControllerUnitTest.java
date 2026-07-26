package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.SleepRoomResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.SleepRoomService;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link SleepRoomController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link SleepRoomService} and the real
 * {@link GlobalExceptionHandler}. Verifies request/response wiring and delegation to the service.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('SLEEP_ROOMS')")} gate on
 * both routes is Spring method-security (AOP), which is NOT active in a standalone MockMvc setup —
 * that feature gate is covered by the integration test. Only two routes exist and neither has a
 * request body, a path variable, an enum, or a required param, so the malformed-JSON /
 * missing-param / invalid-enum / invalid-UUID edge cases do not apply here; {@code name} is an
 * optional free-form query param.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SleepRoomController (unit)")
class SleepRoomControllerUnitTest {

    private static final String BASE = "/sleep-rooms";
    private static final String ROOM_ID = "room-uuid-1";
    private static final String CREATE_CODE = "TM_994";
    private static final String CREATE_MESSAGE = "Sleep room created";
    private static final String LIST_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private SleepRoomService sleepRoomService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        SleepRoomController controller = new SleepRoomController(sleepRoomService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("sleeper").email("s@e.com").name("Sleepy Head")
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

    private static SleepRoomResponse room(String id, String name) {
        return SleepRoomResponse.builder()
                .id(id)
                .name(name)
                .description("Wind down together")
                .category("SLEEP")
                .roomMode("SLEEP_COMPANION")
                .createdAt(Instant.parse("2026-07-22T00:00:00Z"))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /sleep-rooms  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /sleep-rooms")
    class CreateSleepRoom {

        @Test
        void shouldCreateRoomWithNameAndForwardUserAndName() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any())).thenReturn(room(ROOM_ID, "Calm Cove"));

            mockMvc.perform(post(BASE).param("name", "Calm Cove"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(CREATE_CODE))
                    .andExpect(jsonPath("$.message").value(CREATE_MESSAGE))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID))
                    .andExpect(jsonPath("$.data.name").value("Calm Cove"))
                    .andExpect(jsonPath("$.data.roomMode").value("SLEEP_COMPANION"))
                    .andExpect(jsonPath("$.data.category").value("SLEEP"))
                    .andExpect(jsonPath("$.data.description").value("Wind down together"));

            // The authenticated principal's user + the raw name reach the service.
            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(sleepRoomService).createSleepRoom(eq(testUser), name.capture());
            assertThat(name.getValue()).isEqualTo("Calm Cove");
            verify(sleepRoomService, never()).listSleepRooms();
        }

        @Test
        void shouldCreateRoomWithoutNameWhenParamAbsent() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), isNull())).thenReturn(room(ROOM_ID, null));

            mockMvc.perform(post(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(CREATE_CODE))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID));

            // name is @RequestParam(required = false) → null forwarded, no missing-param 500.
            verify(sleepRoomService).createSleepRoom(eq(testUser), isNull());
        }

        @Test
        void shouldForwardBlankNameVerbatim() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any())).thenReturn(room(ROOM_ID, ""));

            mockMvc.perform(post(BASE).param("name", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(CREATE_CODE));

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(sleepRoomService).createSleepRoom(eq(testUser), name.capture());
            assertThat(name.getValue()).isEmpty();
        }

        @Test
        void shouldPassThroughUnicodeAndEmojiName() throws Exception {
            authenticate();
            String tricky = "夜のカフェ 🌙😴 café";
            when(sleepRoomService.createSleepRoom(any(), any())).thenReturn(room(ROOM_ID, tricky));

            mockMvc.perform(post(BASE).param("name", tricky))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value(tricky));

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(sleepRoomService).createSleepRoom(eq(testUser), name.capture());
            assertThat(name.getValue()).isEqualTo(tricky);
        }

        @Test
        void shouldPassThroughXssAndSqliNameWithoutSanitizing() throws Exception {
            authenticate();
            // The controller does not sanitize free-form input; it is forwarded verbatim and any
            // escaping/sanitization is the responsibility of the persistence/render layers.
            String payload = "<script>alert(1)</script>'; DROP TABLE rooms;--";
            when(sleepRoomService.createSleepRoom(any(), any())).thenReturn(room(ROOM_ID, payload));

            mockMvc.perform(post(BASE).param("name", payload))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(sleepRoomService).createSleepRoom(eq(testUser), name.capture());
            assertThat(name.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new NotFoundException("Owner not found", "TM_024"));

            mockMvc.perform(post(BASE).param("name", "Calm Cove"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new ForbiddenException("Guests cannot host sleep rooms", "TM_005"));

            mockMvc.perform(post(BASE).param("name", "Calm Cove"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new ConflictException("You already host an active sleep room", "TM_409"));

            mockMvc.perform(post(BASE).param("name", "Calm Cove"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new BadRequestException("Name too long", "TM_400"));

            mockMvc.perform(post(BASE).param("name", "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn400WithTm071WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad name"));

            mockMvc.perform(post(BASE).param("name", "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(sleepRoomService.createSleepRoom(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE).param("name", "Calm Cove"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /sleep-rooms  (list)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /sleep-rooms")
    class ListSleepRooms {

        @Test
        void shouldReturn200WithRoomList() throws Exception {
            authenticate();
            when(sleepRoomService.listSleepRooms())
                    .thenReturn(List.of(room(ROOM_ID, "Calm Cove"), room("room-uuid-2", "Night Nook")));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(LIST_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value(ROOM_ID))
                    .andExpect(jsonPath("$.data[0].roomMode").value("SLEEP_COMPANION"))
                    .andExpect(jsonPath("$.data[1].id").value("room-uuid-2"))
                    .andExpect(jsonPath("$.data[1].name").value("Night Nook"));

            verify(sleepRoomService).listSleepRooms();
            verify(sleepRoomService, never()).createSleepRoom(any(), any());
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(sleepRoomService.listSleepRooms()).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(LIST_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(sleepRoomService).listSleepRooms();
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(sleepRoomService.listSleepRooms()).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
