package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MusicPlayRequest;
import com.chat.talkMe.dto.response.MusicSessionState;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MusicSessionService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link MusicSessionController} (feature #17, MUSIC_SESSION).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link MusicSessionService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>{@code @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")} is enforced by Spring's
 *       method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup —
 *       the entitlement gate is covered by the integration test. Here we verify request/response
 *       wiring and delegation to the service (which owns the chat-membership / IDOR guard).</li>
 *   <li>The request DTOs ({@link MusicPlayRequest}, MusicSeekRequest, MusicReactRequest) carry
 *       <b>no</b> Bean-Validation annotations and the controller uses no {@code @Valid}, so there
 *       is no {@code MethodArgumentNotValidException}/{@code VE_101} path — all field validation is
 *       done inside the service and surfaces as {@link BadRequestException} (400, service-specific
 *       code). A {@link LocalValidatorFactoryBean} is still wired for parity with the repo template.</li>
 *   <li>No request body has an unboxed primitive (all fields are String / boxed {@code Double}), so
 *       the tolerant Jackson converter used by MessageController's test is intentionally omitted.
 *       No endpoint takes {@code Pageable} or {@code @RequestParam}.</li>
 * </ul>
 *
 * <p>Success codes are read directly from the controller: every endpoint returns
 * {@code messageCode == "TM_000"} (getSession via {@code success(data)} → message "Success";
 * play/pause/seek/react via {@code success(state, "<verb>", "TM_000")}). Thrown codes asserted here
 * mirror the real {@code MusicSessionServiceImpl} (TM_800/801/802/803, TM_400, TM_103) but the
 * service is mocked, so they merely prove the {@link GlobalExceptionHandler} status/code mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MusicSessionController (unit)")
class MusicSessionControllerUnitTest {

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String BASE = "/chats/" + CHAT_ID + "/music";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private MusicSessionService musicSessionService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        MusicSessionController controller = new MusicSessionController(musicSessionService);

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

    /** A "live", playing session state. */
    private static MusicSessionState playingState() {
        return MusicSessionState.builder()
                .trackId("itunes-42")
                .url("https://cdn.example.com/preview.m4a")
                .title("Song Title")
                .artist("Some Artist")
                .artworkUrl("https://cdn.example.com/art.jpg")
                .positionSec(12.5)
                .playing(true)
                .updatedAtEpochMs(1_700_000_000_000L)
                .hostUsername("testuser")
                .serverTimeEpochMs(1_700_000_001_000L)
                .build();
    }

    /** The empty (not-playing) shell the service hands back when there is no live session. */
    private static MusicSessionState notPlayingShell() {
        return MusicSessionState.builder()
                .playing(false)
                .positionSec(0)
                .updatedAtEpochMs(1_700_000_000_000L)
                .serverTimeEpochMs(1_700_000_000_000L)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/music
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats/{chatId}/music")
    class GetSession {

        @Test
        void shouldReturn200WithLiveSessionState() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any())).thenReturn(playingState());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.trackId").value("itunes-42"))
                    .andExpect(jsonPath("$.data.url").value("https://cdn.example.com/preview.m4a"))
                    .andExpect(jsonPath("$.data.title").value("Song Title"))
                    .andExpect(jsonPath("$.data.positionSec").value(12.5))
                    .andExpect(jsonPath("$.data.playing").value(true))
                    .andExpect(jsonPath("$.data.hostUsername").value("testuser"));

            // Path variable + authenticated user reach the service.
            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            verify(musicSessionService).getSession(eq(testUser), chatId.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT_ID);
            verify(musicSessionService, never()).play(any(), any(), any());
        }

        @Test
        void shouldReturn200WithNotPlayingShellWhenNoSession() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any())).thenReturn(notPlayingShell());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.playing").value(false))
                    .andExpect(jsonPath("$.data.positionSec").value(0.0))
                    .andExpect(jsonPath("$.data.trackId").doesNotExist());
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenInvalidChatId() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new BadRequestException("Invalid chat id", "TM_400"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any())).thenThrow(new RuntimeException("redis down"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/music/play
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatId}/music/play")
    class Play {

        @Test
        void shouldReturn200AndForwardTrackFields() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trackId\":\"itunes-42\",\"url\":\"https://cdn.example.com/preview.m4a\","
                                    + "\"title\":\"Song Title\",\"artist\":\"Some Artist\","
                                    + "\"artworkUrl\":\"https://cdn.example.com/art.jpg\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Playing"))
                    .andExpect(jsonPath("$.data.playing").value(true))
                    .andExpect(jsonPath("$.data.url").value("https://cdn.example.com/preview.m4a"));

            ArgumentCaptor<MusicPlayRequest> req = ArgumentCaptor.forClass(MusicPlayRequest.class);
            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), req.capture());
            assertThat(req.getValue().getTrackId()).isEqualTo("itunes-42");
            assertThat(req.getValue().getUrl()).isEqualTo("https://cdn.example.com/preview.m4a");
            assertThat(req.getValue().getTitle()).isEqualTo("Song Title");
            assertThat(req.getValue().getArtist()).isEqualTo("Some Artist");
            assertThat(req.getValue().getArtworkUrl()).isEqualTo("https://cdn.example.com/art.jpg");
            assertThat(req.getValue().getPositionSec()).isNull();
            verify(musicSessionService, never()).pause(any(), any(), any());
        }

        @Test
        void shouldForwardPositionSecWhenProvided() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\",\"positionSec\":37.25}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<MusicPlayRequest> req = ArgumentCaptor.forClass(MusicPlayRequest.class);
            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), req.capture());
            assertThat(req.getValue().getPositionSec()).isEqualTo(37.25);
        }

        @Test
        void shouldForwardNullOptionalFieldsWhenOnlyUrlPresent() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/only-url.m4a\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<MusicPlayRequest> req = ArgumentCaptor.forClass(MusicPlayRequest.class);
            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), req.capture());
            assertThat(req.getValue().getUrl()).isEqualTo("https://cdn.example.com/only-url.m4a");
            assertThat(req.getValue().getTrackId()).isNull();
            assertThat(req.getValue().getTitle()).isNull();
            assertThat(req.getValue().getArtist()).isNull();
            assertThat(req.getValue().getArtworkUrl()).isNull();
            assertThat(req.getValue().getPositionSec()).isNull();
        }

        @Test
        void shouldPassThroughUnicodeAndEmojiInTitle() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\",\"title\":\"日本語 🎵 café\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<MusicPlayRequest> req = ArgumentCaptor.forClass(MusicPlayRequest.class);
            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), req.capture());
            assertThat(req.getValue().getTitle()).isEqualTo("日本語 🎵 café");
        }

        @Test
        void shouldPassThroughXssAndSqliInFreeFormTitleUnchanged() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenReturn(playingState());
            String payload = "<script>alert(1)</script>'; DROP TABLE users;--";

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\","
                                    + "\"title\":\"<script>alert(1)</script>'; DROP TABLE users;--\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<MusicPlayRequest> req = ArgumentCaptor.forClass(MusicPlayRequest.class);
            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), req.capture());
            // Controller does not sanitize; the raw string is passed straight to the service.
            assertThat(req.getValue().getTitle()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400WhenUrlBlank() throws Exception {
            authenticate();
            // Blank-url validation lives in the service, so it IS invoked before throwing.
            when(musicSessionService.play(any(), any(), any()))
                    .thenThrow(new BadRequestException("A playable track url is required", "TM_800"));

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_800"));

            verify(musicSessionService).play(eq(testUser), eq(CHAT_ID), any());
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenServiceReportsConflict() throws Exception {
            authenticate();
            // Generic ServiceException(409) mapping contract (impl doesn't currently throw this).
            when(musicSessionService.play(any(), any(), any()))
                    .thenThrow(new ConflictException("Session busy", "TM_409"));

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pinned).
            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // @RequestBody is required → missing body → HttpMessageNotReadableException → 500 (pinned).
            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(musicSessionService.play(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/play").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://cdn.example.com/p.m4a\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/music/pause   (body optional: @RequestBody(required = false))
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatId}/music/pause")
    class Pause {

        @Test
        void shouldReturn200AndForwardPositionWhenBodyProvided() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), any())).thenReturn(notPlayingShell());

            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":48.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Paused"))
                    .andExpect(jsonPath("$.data.playing").value(false));

            ArgumentCaptor<Double> pos = ArgumentCaptor.forClass(Double.class);
            verify(musicSessionService).pause(eq(testUser), eq(CHAT_ID), pos.capture());
            assertThat(pos.getValue()).isEqualTo(48.0);
        }

        @Test
        void shouldReturn200WithNullPositionWhenBodyAbsent() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), isNull())).thenReturn(notPlayingShell());

            // No body at all — required=false ⇒ controller passes null position through.
            mockMvc.perform(post(BASE + "/pause"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"));

            verify(musicSessionService).pause(eq(testUser), eq(CHAT_ID), isNull());
        }

        @Test
        void shouldReturn200WithNullPositionWhenEmptyJsonBody() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), isNull())).thenReturn(notPlayingShell());

            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            verify(musicSessionService).pause(eq(testUser), eq(CHAT_ID), isNull());
        }

        @Test
        void shouldReturn400WhenNoActiveSession() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), any()))
                    .thenThrow(new BadRequestException("No active music session", "TM_802"));

            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":5.0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_802"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Even with required=false, a present-but-unparseable body → 500 (pinned).
            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(musicSessionService.pause(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/music/seek   (body required)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatId}/music/seek")
    class Seek {

        @Test
        void shouldReturn200AndForwardPosition() throws Exception {
            authenticate();
            when(musicSessionService.seek(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":90.5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Seeked"));

            ArgumentCaptor<Double> pos = ArgumentCaptor.forClass(Double.class);
            verify(musicSessionService).seek(eq(testUser), eq(CHAT_ID), pos.capture());
            assertThat(pos.getValue()).isEqualTo(90.5);
        }

        @Test
        void shouldReturn400WhenPositionMissingInBody() throws Exception {
            authenticate();
            // Empty JSON ⇒ positionSec null ⇒ controller forwards null ⇒ service rejects (TM_801).
            when(musicSessionService.seek(any(), any(), isNull()))
                    .thenThrow(new BadRequestException("positionSec is required", "TM_801"));

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_801"));

            verify(musicSessionService).seek(eq(testUser), eq(CHAT_ID), isNull());
        }

        @Test
        void shouldReturn400WhenPositionNegative() throws Exception {
            authenticate();
            when(musicSessionService.seek(any(), any(), any()))
                    .thenThrow(new BadRequestException("positionSec is required", "TM_801"));

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":-5.0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_801"));
        }

        @Test
        void shouldReturn400WhenNoActiveSession() throws Exception {
            authenticate();
            when(musicSessionService.seek(any(), any(), any()))
                    .thenThrow(new BadRequestException("No active music session", "TM_802"));

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":10.0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_802"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(musicSessionService.seek(any(), any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":10.0}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // @RequestBody required ⇒ missing body ⇒ HttpMessageNotReadableException ⇒ 500 (pinned).
            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(musicSessionService.seek(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/seek").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSec\":10.0}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/music/react   (body required)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/{chatId}/music/react")
    class React {

        @Test
        void shouldReturn200AndForwardEmoji() throws Exception {
            authenticate();
            when(musicSessionService.react(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emoji\":\":fire:\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Reacted"));

            ArgumentCaptor<String> emoji = ArgumentCaptor.forClass(String.class);
            verify(musicSessionService).react(eq(testUser), eq(CHAT_ID), emoji.capture());
            assertThat(emoji.getValue()).isEqualTo(":fire:");
        }

        @Test
        void shouldPassThroughUnicodeEmoji() throws Exception {
            authenticate();
            when(musicSessionService.react(any(), any(), any())).thenReturn(playingState());

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emoji\":\"🎧🔥\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> emoji = ArgumentCaptor.forClass(String.class);
            verify(musicSessionService).react(eq(testUser), eq(CHAT_ID), emoji.capture());
            assertThat(emoji.getValue()).isEqualTo("🎧🔥");
        }

        @Test
        void shouldReturn400WhenEmojiMissingInBody() throws Exception {
            authenticate();
            // Empty JSON ⇒ emoji null ⇒ controller forwards null ⇒ service rejects (TM_803).
            when(musicSessionService.react(any(), any(), isNull()))
                    .thenThrow(new BadRequestException("emoji is required", "TM_803"));

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_803"));

            verify(musicSessionService).react(eq(testUser), eq(CHAT_ID), isNull());
        }

        @Test
        void shouldReturn400WhenNoActiveSession() throws Exception {
            authenticate();
            when(musicSessionService.react(any(), any(), any()))
                    .thenThrow(new BadRequestException("No active music session", "TM_802"));

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emoji\":\"👍\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_802"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(musicSessionService.react(any(), any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emoji\":\"👍\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicSessionService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(musicSessionService.react(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/react").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emoji\":\"👍\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GlobalExceptionHandler mapping contract (exercised via GET /music)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GlobalExceptionHandler mapping")
    class ExceptionMapping {

        @Test
        void shouldMapNotFoundTo404WithCode() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldMapConflictTo409WithCode() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new ConflictException("Conflict", "TM_409"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldMapAccessDeniedTo403WithTm005() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldMapIllegalArgumentTo400WithTm071() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad argument"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldMapInvalidUuidIllegalArgumentToTmInvalidUuid() throws Exception {
            authenticate();
            when(musicSessionService.getSession(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: xyz"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }
    }
}
