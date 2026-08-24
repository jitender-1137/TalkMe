package com.neo.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateLanguageRoomRequest;
import com.neo.chat.dto.request.CreateTopicRoomRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.LiveRoomResponse;
import com.neo.chat.dto.response.TranslateResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.LiveRoomService;
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
 * Pure controller unit test for {@link LiveRoomController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link LiveRoomService}, the real
 * {@link GlobalExceptionHandler}, a bean validator and the auth-principal resolver. Verifies
 * request/response wiring, body/path binding, bean validation, and error passthrough.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check(...)")} gates are Spring
 * method-security (AOP), which is NOT active in a standalone MockMvc setup — those gates are
 * covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveRoomController (unit)")
class LiveRoomControllerUnitTest {

    private static final String BASE = "/rooms/live";
    private static final String ROOM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OK_CODE = "TM_000";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private LiveRoomService liveRoomService;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private User testUser;

    @BeforeEach
    void setUp() {
        LiveRoomController controller = new LiveRoomController(liveRoomService);

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

    private static LiveRoomResponse room(String id, String mode) {
        return LiveRoomResponse.builder()
                .id(id).name("Room").description("desc").category("coffee")
                .roomMode(mode).memberCount(5).liveCount(2)
                .createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
    }

    // ── POST /topic ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /rooms/live/topic")
    class CreateTopic {

        @Test
        void createsTopicRoomAndForwardsFields() throws Exception {
            authenticate();
            CreateTopicRoomRequest body = CreateTopicRoomRequest.builder()
                    .name("Retro Nights").category("gaming").tags(List.of("GAMING")).build();
            when(liveRoomService.createTopicRoom(any(), any(), any(), any()))
                    .thenReturn(room(ROOM_ID, "TOPIC"));

            mockMvc.perform(post(BASE + "/topic").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.message").value("Topic room created"))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID))
                    .andExpect(jsonPath("$.data.roomMode").value("TOPIC"));

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(liveRoomService).createTopicRoom(eq(testUser), name.capture(), eq("gaming"), eq(List.of("GAMING")));
            assertThat(name.getValue()).isEqualTo("Retro Nights");
        }

        @Test
        void rejectsBlankNameWithValidationError() throws Exception {
            authenticate();
            CreateTopicRoomRequest body = CreateTopicRoomRequest.builder()
                    .name("   ").category("gaming").build();

            mockMvc.perform(post(BASE + "/topic").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verify(liveRoomService, never()).createTopicRoom(any(), any(), any(), any());
        }

        @Test
        void rejectsMissingCategoryWithValidationError() throws Exception {
            authenticate();
            CreateTopicRoomRequest body = CreateTopicRoomRequest.builder().name("Room").build();

            mockMvc.perform(post(BASE + "/topic").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void malformedJsonHandledByGlobalAdvice() throws Exception {
            // HttpMessageNotReadableException is caught by the catch-all @ExceptionHandler(Exception)
            // in GlobalExceptionHandler (which runs before the default 400 resolver) → 500 / TM_002.
            authenticate();
            mockMvc.perform(post(BASE + "/topic").contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void returns500OnUnexpectedError() throws Exception {
            authenticate();
            CreateTopicRoomRequest body = CreateTopicRoomRequest.builder().name("Room").category("gaming").build();
            when(liveRoomService.createTopicRoom(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/topic").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── POST /language ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /rooms/live/language")
    class CreateLanguage {

        @Test
        void createsLanguageRoomAndForwardsFields() throws Exception {
            authenticate();
            CreateLanguageRoomRequest body = CreateLanguageRoomRequest.builder()
                    .name("Hindi <-> English").targetLanguage("EN").nativeLanguage("HI").build();
            when(liveRoomService.createLanguageRoom(any(), any(), any(), any()))
                    .thenReturn(room(ROOM_ID, "LANGUAGE_PRACTICE"));

            mockMvc.perform(post(BASE + "/language").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.message").value("Language room created"))
                    .andExpect(jsonPath("$.data.roomMode").value("LANGUAGE_PRACTICE"));

            verify(liveRoomService).createLanguageRoom(eq(testUser), eq("Hindi <-> English"), eq("EN"), eq("HI"));
        }

        @Test
        void rejectsMissingTargetLanguage() throws Exception {
            authenticate();
            CreateLanguageRoomRequest body = CreateLanguageRoomRequest.builder().nativeLanguage("HI").build();

            mockMvc.perform(post(BASE + "/language").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verify(liveRoomService, never()).createLanguageRoom(any(), any(), any(), any());
        }
    }

    // ── GET /topic, GET /language ────────────────────────────────────────────

    @Nested
    @DisplayName("GET lists")
    class Lists {

        @Test
        void listsTopicRooms() throws Exception {
            authenticate();
            when(liveRoomService.listTopicRooms()).thenReturn(List.of(room(ROOM_ID, "TOPIC")));

            mockMvc.perform(get(BASE + "/topic"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].roomMode").value("TOPIC"));

            verify(liveRoomService).listTopicRooms();
            verify(liveRoomService, never()).listLanguageRooms();
        }

        @Test
        void listsLanguageRooms() throws Exception {
            authenticate();
            when(liveRoomService.listLanguageRooms()).thenReturn(List.of(room(ROOM_ID, "LANGUAGE_PRACTICE")));

            mockMvc.perform(get(BASE + "/language"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].roomMode").value("LANGUAGE_PRACTICE"));

            verify(liveRoomService).listLanguageRooms();
        }
    }

    // ── join / enter / leave ─────────────────────────────────────────────────

    @Nested
    @DisplayName("join / enter / leave")
    class Presence {

        @Test
        void joinsRoom() throws Exception {
            authenticate();
            when(liveRoomService.joinRoom(any(), eq(ROOM_ID))).thenReturn(room(ROOM_ID, "TOPIC"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/join"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Joined room"))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID));

            verify(liveRoomService).joinRoom(testUser, ROOM_ID);
        }

        @Test
        void entersRoom() throws Exception {
            authenticate();
            when(liveRoomService.enterRoom(any(), eq(ROOM_ID))).thenReturn(room(ROOM_ID, "TOPIC"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/enter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Entered room"))
                    .andExpect(jsonPath("$.data.liveCount").value(2));

            verify(liveRoomService).enterRoom(testUser, ROOM_ID);
        }

        @Test
        void leavesRoom() throws Exception {
            authenticate();

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/leave"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Left room"));

            verify(liveRoomService).leaveRoom(testUser, ROOM_ID);
        }

        @Test
        void enterUnknownRoomReturns404() throws Exception {
            authenticate();
            when(liveRoomService.enterRoom(any(), eq(ROOM_ID)))
                    .thenThrow(new NotFoundException("Room not found", "TM_926"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/enter"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_926"));
        }
    }

    // ── translate ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /{uuid}/translate")
    class Translate {

        @Test
        void translatesInRoom() throws Exception {
            authenticate();
            TranslateRequest body = TranslateRequest.builder().text("hola").target("en").build();
            when(liveRoomService.translateInRoom(any(), eq(ROOM_ID), any()))
                    .thenReturn(TranslateResponse.builder().translatedText("hello").target("en").build());

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/translate").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Translated"))
                    .andExpect(jsonPath("$.data.translatedText").value("hello"));

            verify(liveRoomService).translateInRoom(eq(testUser), eq(ROOM_ID), any());
        }

        @Test
        void rejectsBlankTextWithValidationError() throws Exception {
            authenticate();
            TranslateRequest body = TranslateRequest.builder().text("  ").target("en").build();

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/translate").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verify(liveRoomService, never()).translateInRoom(any(), any(), any());
        }

        @Test
        void nonLanguageRoomReturns400() throws Exception {
            authenticate();
            TranslateRequest body = TranslateRequest.builder().text("hi").target("en").build();
            when(liveRoomService.translateInRoom(any(), eq(ROOM_ID), any()))
                    .thenThrow(new BadRequestException("Not a language room", "TM_927"));

            mockMvc.perform(post(BASE + "/" + ROOM_ID + "/translate").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_927"));
        }
    }

    // ── GET /{uuid}/topics ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{uuid}/topics")
    class Topics {

        @Test
        void returnsStarters() throws Exception {
            authenticate();
            when(liveRoomService.suggestTopics(ROOM_ID)).thenReturn(List.of("Coffee or tea?", "Beach or mountains?"));

            mockMvc.perform(get(BASE + "/" + ROOM_ID + "/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0]").value("Coffee or tea?"));

            verify(liveRoomService).suggestTopics(ROOM_ID);
        }

        @Test
        void unknownRoomReturns404() throws Exception {
            authenticate();
            when(liveRoomService.suggestTopics(ROOM_ID))
                    .thenThrow(new NotFoundException("Room not found", "TM_926"));

            mockMvc.perform(get(BASE + "/" + ROOM_ID + "/topics"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_926"));
        }

        @Test
        void forbiddenPropagates() throws Exception {
            authenticate();
            when(liveRoomService.suggestTopics(ROOM_ID))
                    .thenThrow(new ForbiddenException("nope", "TM_005"));

            mockMvc.perform(get(BASE + "/" + ROOM_ID + "/topics"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }
    }
}
