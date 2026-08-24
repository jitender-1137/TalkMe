package com.neo.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateStudyRoomRequest;
import com.neo.chat.dto.request.SetGoalRequest;
import com.neo.chat.dto.response.StudyRoomResponse;
import com.neo.chat.dto.response.StudySessionResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.StudyRoomService;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link StudyRoomController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link StudyRoomService}, the real
 * {@link GlobalExceptionHandler}, a {@link LocalValidatorFactoryBean} and the
 * {@link AuthenticationPrincipalArgumentResolver}. Verifies request/response wiring and delegation.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('STUDY_ROOMS')")} /
 * {@code hasRole('USER')} gates are Spring method-security (AOP), NOT active in a standalone MockMvc
 * setup — that gating is covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StudyRoomController (unit)")
class StudyRoomControllerUnitTest {

    private static final String BASE = "/rooms/study";
    private static final String ROOM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CREATE_CODE = "TM_906";
    private static final String START_CODE = "TM_907";
    private static final String OK_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private StudyRoomService studyRoomService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        StudyRoomController controller = new StudyRoomController(studyRoomService);

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
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static StudyRoomResponse room() {
        return StudyRoomResponse.builder()
                .id(ROOM_ID).name("Calc Grind").subject("Calculus")
                .description("desc").roomMode("STUDY_POMODORO")
                .phase("IDLE").remainingSeconds(0).participantCount(3)
                .build();
    }

    private static StudySessionResponse session(String phase, long remaining) {
        return StudySessionResponse.builder()
                .roomUuid(ROOM_ID).subject("Calculus").phase(phase)
                .phaseEndsAtEpochMs(System.currentTimeMillis() + remaining * 1000)
                .remainingSeconds(remaining).focusMinutes(25).breakMinutes(5)
                .ownerUsername("alice").active(!"IDLE".equals(phase))
                .serverTimeEpochMs(System.currentTimeMillis()).build();
    }

    // ── POST /rooms/study (create) ────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /rooms/study")
    class Create {

        @Test
        void createsWithBody() throws Exception {
            authenticate();
            when(studyRoomService.createStudyRoom(any(), any(), any(), any(), any())).thenReturn(room());
            CreateStudyRoomRequest req = new CreateStudyRoomRequest("Calc Grind", "Calculus", 50, 10);

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(CREATE_CODE))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID))
                    .andExpect(jsonPath("$.data.roomMode").value("STUDY_POMODORO"))
                    .andExpect(jsonPath("$.data.subject").value("Calculus"))
                    .andExpect(jsonPath("$.data.participantCount").value(3));

            verify(studyRoomService).createStudyRoom(eq(testUser), eq("Calc Grind"), eq("Calculus"), eq(50), eq(10));
        }

        @Test
        void createsWithoutBody() throws Exception {
            authenticate();
            when(studyRoomService.createStudyRoom(any(), any(), any(), any(), any())).thenReturn(room());

            mockMvc.perform(post(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(CREATE_CODE));

            // No body → all fields null forwarded (service applies defaults).
            verify(studyRoomService).createStudyRoom(eq(testUser), eq(null), eq(null), eq(null), eq(null));
        }

        @Test
        void rejectsOutOfRangeMinutesWithValidation() throws Exception {
            authenticate();
            CreateStudyRoomRequest req = new CreateStudyRoomRequest("x", null, 9999, 5);

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            verify(studyRoomService, never()).createStudyRoom(any(), any(), any(), any(), any());
        }

        @Test
        void mapsInternalError() throws Exception {
            authenticate();
            when(studyRoomService.createStudyRoom(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── GET /rooms/study (list) ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /rooms/study")
    class ListRooms {

        @Test
        void listsRooms() throws Exception {
            authenticate();
            when(studyRoomService.listStudyRooms()).thenReturn(List.of(room()));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(ROOM_ID))
                    .andExpect(jsonPath("$.data[0].phase").value("IDLE"));

            verify(studyRoomService).listStudyRooms();
        }

        @Test
        void emptyList() throws Exception {
            authenticate();
            when(studyRoomService.listStudyRooms()).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ── join / enter / leave ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("join / enter / leave")
    class Roster {

        @Test
        void join() throws Exception {
            authenticate();
            when(studyRoomService.joinStudyRoom(eq(testUser), eq(ROOM_ID))).thenReturn(room());

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/join"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID));

            verify(studyRoomService).joinStudyRoom(testUser, ROOM_ID);
        }

        @Test
        void enter() throws Exception {
            authenticate();
            when(studyRoomService.enter(eq(testUser), eq(ROOM_ID))).thenReturn(room());

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/enter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participantCount").value(3));

            verify(studyRoomService).enter(testUser, ROOM_ID);
        }

        @Test
        void leave() throws Exception {
            authenticate();

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/leave"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(studyRoomService).leave(testUser, ROOM_ID);
        }

        @Test
        void enterMapsNotFound() throws Exception {
            authenticate();
            when(studyRoomService.enter(any(), any()))
                    .thenThrow(new NotFoundException("Study room not found", "TM_901"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/enter"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_901"));
        }
    }

    // ── start / session ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("start / session")
    class Timer {

        @Test
        void start() throws Exception {
            authenticate();
            when(studyRoomService.startTimer(eq(testUser), eq(ROOM_ID))).thenReturn(session("FOCUS", 1500));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(START_CODE))
                    .andExpect(jsonPath("$.data.phase").value("FOCUS"))
                    .andExpect(jsonPath("$.data.remainingSeconds").value(1500));

            verify(studyRoomService).startTimer(testUser, ROOM_ID);
        }

        @Test
        void startForbiddenForNonOwner() throws Exception {
            authenticate();
            when(studyRoomService.startTimer(any(), any()))
                    .thenThrow(new ForbiddenException("Only the room owner can start the timer", "TM_903"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/start"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_903"));
        }

        @Test
        void currentSession() throws Exception {
            authenticate();
            when(studyRoomService.getSession(ROOM_ID)).thenReturn(session("IDLE", 0));

            mockMvc.perform(get(BASE + "/" + ROOM_ID + "/session"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.phase").value("IDLE"));

            verify(studyRoomService).getSession(ROOM_ID);
        }
    }

    // ── stuck / goal ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stuck / goal")
    class StuckAndGoal {

        @Test
        void stuckWithNote() throws Exception {
            authenticate();
            when(studyRoomService.imStuck(eq(testUser), eq(ROOM_ID), eq("stuck on limits")))
                    .thenReturn(session("FOCUS", 900));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/stuck")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"stuck on limits\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.phase").value("FOCUS"));

            verify(studyRoomService).imStuck(testUser, ROOM_ID, "stuck on limits");
        }

        @Test
        void stuckWithoutBody() throws Exception {
            authenticate();
            when(studyRoomService.imStuck(eq(testUser), eq(ROOM_ID), eq(null)))
                    .thenReturn(session("FOCUS", 900));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/stuck"))
                    .andExpect(status().isOk());

            verify(studyRoomService).imStuck(testUser, ROOM_ID, null);
        }

        @Test
        void setGoal() throws Exception {
            authenticate();
            when(studyRoomService.setGoal(eq(testUser), eq(ROOM_ID), eq("Finish ch.3")))
                    .thenReturn(session("IDLE", 0));
            SetGoalRequest req = new SetGoalRequest("Finish ch.3");

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/goal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE));

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(studyRoomService).setGoal(eq(testUser), eq(ROOM_ID), text.capture());
            assertThat(text.getValue()).isEqualTo("Finish ch.3");
        }

        @Test
        void setGoalMapsBadRequest() throws Exception {
            authenticate();
            when(studyRoomService.setGoal(any(), any(), any()))
                    .thenThrow(new BadRequestException("Goal text is required", "TM_904"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/goal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_904"));
        }
    }
}
