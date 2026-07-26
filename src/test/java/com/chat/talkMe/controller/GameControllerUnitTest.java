package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.GameSessionResponse;
import com.chat.talkMe.enums.GameType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GameService;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link GameController} (Conversation Games, feature #13).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link GameService} and the real
 * {@link GlobalExceptionHandler}. No tolerant JSON converter is installed: the only
 * {@code @RequestBody} DTO ({@code GameStartRequest}) carries no unboxed primitives
 * (a {@code String} + a {@code GameType} enum), so the default converter suffices. No
 * endpoint takes a {@code Pageable}, so no {@code PageableHandlerMethodArgumentResolver}
 * is registered.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} and
 * the per-route {@code @featureGuard.check('CONVERSATION_GAMES')} entitlement gates are
 * enforced by Spring method-security, which is INACTIVE in standalone MockMvc — those are
 * covered by an integration test. Chat membership / feature authorization lives in the
 * service and is driven here by stubbed exceptions.
 *
 * <p><b>Enum-in-body note:</b> {@code gameType} is bound by Jackson (not a controller-side
 * {@code Enum.valueOf}), so an unknown enum token fails deserialization →
 * {@code HttpMessageNotReadableException}, which has no dedicated handler and therefore
 * falls through the catch-all to 500/TM_002 — same path as malformed JSON. A MISSING
 * {@code gameType} instead trips {@code @NotNull} → 400/VE_101. Both are verified below.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GameController (unit)")
class GameControllerUnitTest {

    private static final String BASE = "/games";
    private static final String GAME_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String CHAT_ID = "chat-uuid-1";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INVALID_ARG_CODE = "TM_071";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private GameService gameService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        GameController controller = new GameController(gameService);

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

    private static GameSessionResponse session(String state, int round, String prompt) {
        return GameSessionResponse.builder()
                .uuid(GAME_UUID)
                .gameType(GameType.TWO_TRUTHS.name())
                .state(state)
                .round(round)
                .prompt(prompt)
                .build();
    }

    private static GameSessionResponse inProgress() {
        return session("IN_PROGRESS", 1, "Tell me two truths and a lie.");
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /games/start
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /games/start")
    class Start {

        @Test
        void shouldReturn200AndForwardUserChatIdAndGameType() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any())).thenReturn(inProgress());

            String body = """
                    {"chatId":"%s","gameType":"TWO_TRUTHS"}""".formatted(CHAT_ID);
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.uuid").value(GAME_UUID))
                    .andExpect(jsonPath("$.data.gameType").value("TWO_TRUTHS"))
                    .andExpect(jsonPath("$.data.state").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.round").value(1))
                    .andExpect(jsonPath("$.data.prompt").value("Tell me two truths and a lie."));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<GameType> type = ArgumentCaptor.forClass(GameType.class);
            verify(gameService).start(eq(testUser), chatId.capture(), type.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT_ID);
            assertThat(type.getValue()).isEqualTo(GameType.TWO_TRUTHS);
        }

        @Test
        void shouldForwardEachGameTypeToService() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any())).thenReturn(inProgress());

            String body = """
                    {"chatId":"%s","gameType":"WOULD_YOU_RATHER"}""".formatted(CHAT_ID);
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            verify(gameService).start(eq(testUser), eq(CHAT_ID), eq(GameType.WOULD_YOU_RATHER));
        }

        @Test
        void shouldPassThroughUnicodeAndEmojiChatId() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any())).thenReturn(inProgress());

            String weird = "chat-😀-café-Ω";
            String body = """
                    {"chatId":"%s","gameType":"THIS_OR_THAT"}""".formatted(weird);
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            verify(gameService).start(eq(testUser), eq(weird), eq(GameType.THIS_OR_THAT));
        }

        @Test
        void shouldPassThroughXssAndSqliChatIdVerbatim() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any())).thenReturn(inProgress());

            // Controller/DTO does no sanitisation — the raw string must reach the service untouched.
            String payload = "<script>alert(1)</script>'; DROP TABLE chats;--";
            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            String body = """
                    {"chatId":%s,"gameType":"RAPID_FIRE"}"""
                    .formatted("\"" + payload.replace("\"", "\\\"") + "\"");
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            verify(gameService).start(eq(testUser), chatId.capture(), eq(GameType.RAPID_FIRE));
            assertThat(chatId.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenChatIdBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"\",\"gameType\":\"TWO_TRUTHS\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenChatIdWhitespaceOnly() throws Exception {
            authenticate();
            // @NotBlank rejects a whitespace-only string.
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"   \",\"gameType\":\"TWO_TRUTHS\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenChatIdMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"gameType\":\"TWO_TRUTHS\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenGameTypeMissing() throws Exception {
            authenticate();
            // @NotNull on gameType — a missing key trips bean validation, not Jackson.
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\"}".formatted(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenGameTypeExplicitlyNull() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":null}".formatted(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenBothFieldsInvalid() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenGameTypeUnknownEnum() throws Exception {
            authenticate();
            // PIN: gameType is deserialized by Jackson (no controller-side Enum.valueOf). An unknown
            // token → HttpMessageNotReadableException, which has NO dedicated handler and so falls to
            // the catch-all → 500/TM_002. It is NOT a controller IllegalArgumentException (would be
            // TM_071) and NOT a bean-validation error (VE_101). If a HttpMessageNotReadable handler
            // is later added mapping bad enums to 400, update this expectation.
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"CHESS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenGameTypeLowercase() throws Exception {
            authenticate();
            // PIN: Jackson matches enum constants by exact name() by default; "two_truths" does not
            // match TWO_TRUTHS → HttpMessageNotReadableException → catch-all 500/TM_002.
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"two_truths\"}".formatted(CHAT_ID)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenBodyMalformed() throws Exception {
            authenticate();
            // PIN: malformed JSON → HttpMessageNotReadableException → no dedicated handler → catch-all
            // 500/TM_002. (No @ExceptionHandler(HttpMessageNotReadableException) is registered.)
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn403WhenFeatureOrChatNotPermitted() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_103"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_101"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn409WhenGameAlreadyInProgress() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new ConflictException("A game is already in progress", "TM_409"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsAsBadRequest() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new BadRequestException("Games only run in private chats", "TM_400"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn400WithTm071WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("bad game config"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_ARG_CODE));
        }

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedRuntime() throws Exception {
            authenticate();
            when(gameService.start(any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/start").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatId\":\"%s\",\"gameType\":\"TWO_TRUTHS\"}".formatted(CHAT_ID)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /games/{uuid}/next
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /games/{uuid}/next")
    class Next {

        @Test
        void shouldReturn200AndForwardUuidAndUser() throws Exception {
            authenticate();
            when(gameService.next(any(), any())).thenReturn(session("IN_PROGRESS", 2, "Round two prompt"));

            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.round").value(2))
                    .andExpect(jsonPath("$.data.prompt").value("Round two prompt"));

            verify(gameService).next(testUser, GAME_UUID);
        }

        @Test
        void shouldReturnEndedSessionWithNullPromptWhenBankExhausted() throws Exception {
            authenticate();
            // Contract: prompt is null once the session has ENDED.
            when(gameService.next(any(), any())).thenReturn(session("ENDED", 7, null));

            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("ENDED"))
                    .andExpect(jsonPath("$.data.prompt").value(nullValue()));

            verify(gameService).next(testUser, GAME_UUID);
        }

        @Test
        void shouldReturn404WhenSessionNotFound() throws Exception {
            authenticate();
            when(gameService.next(any(), any()))
                    .thenThrow(new NotFoundException("Game session not found", "TM_101"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenNotAParticipant() throws Exception {
            authenticate();
            when(gameService.next(any(), any()))
                    .thenThrow(new ForbiddenException("You are not in this game", "TM_103"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenAdvancingEndedSession() throws Exception {
            authenticate();
            when(gameService.next(any(), any()))
                    .thenThrow(new ConflictException("Game has already ended", "TM_409"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn400WithInvalidUuidCodeWhenUuidMalformed() throws Exception {
            authenticate();
            // Service parses the path uuid; "Invalid UUID string" maps to TM_INVALID_UUID / 400.
            when(gameService.next(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));
            mockMvc.perform(post(BASE + "/not-a-uuid/next"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));

            verify(gameService).next(testUser, "not-a-uuid");
        }

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedRuntime() throws Exception {
            authenticate();
            when(gameService.next(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/next"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /games/{uuid}/end
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /games/{uuid}/end")
    class End {

        @Test
        void shouldReturn200AndForwardUuidAndUser() throws Exception {
            authenticate();
            when(gameService.end(any(), any())).thenReturn(session("ENDED", 3, null));

            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/end"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.state").value("ENDED"))
                    .andExpect(jsonPath("$.data.prompt").value(nullValue()));

            verify(gameService).end(testUser, GAME_UUID);
        }

        @Test
        void shouldReturn404WhenSessionNotFound() throws Exception {
            authenticate();
            when(gameService.end(any(), any()))
                    .thenThrow(new NotFoundException("Game session not found", "TM_101"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/end"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenNotAParticipant() throws Exception {
            authenticate();
            when(gameService.end(any(), any()))
                    .thenThrow(new ForbiddenException("You cannot end this game", "TM_103"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/end"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedRuntime() throws Exception {
            authenticate();
            when(gameService.end(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/" + GAME_UUID + "/end"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /games/active
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /games/active")
    class Active {

        @Test
        void shouldReturn200AndForwardChatIdAndUser() throws Exception {
            authenticate();
            when(gameService.active(any(), any())).thenReturn(inProgress());

            mockMvc.perform(get(BASE + "/active").param("chatId", CHAT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.uuid").value(GAME_UUID))
                    .andExpect(jsonPath("$.data.state").value("IN_PROGRESS"));

            verify(gameService).active(testUser, CHAT_ID);
        }

        @Test
        void shouldReturn200WithNullDataWhenNoActiveGame() throws Exception {
            authenticate();
            // Contract: active() returns null when no session is running.
            when(gameService.active(any(), any())).thenReturn(null);

            mockMvc.perform(get(BASE + "/active").param("chatId", CHAT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(gameService).active(testUser, CHAT_ID);
        }

        @Test
        void shouldForwardEmojiChatIdParamVerbatim() throws Exception {
            authenticate();
            when(gameService.active(any(), any())).thenReturn(null);

            String weird = "chat-😀-Ω";
            mockMvc.perform(get(BASE + "/active").param("chatId", weird))
                    .andExpect(status().isOk());

            verify(gameService).active(testUser, weird);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenChatIdParamMissing() throws Exception {
            authenticate();
            // PIN: @RequestParam("chatId") is required (no defaultValue / required=false), so a missing
            // param → MissingServletRequestParameterException, which has no dedicated handler → catch-all
            // 500/TM_002. If the param is later made optional, update this expectation.
            mockMvc.perform(get(BASE + "/active"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(gameService);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(gameService.active(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_101"));
            mockMvc.perform(get(BASE + "/active").param("chatId", CHAT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenNotPermitted() throws Exception {
            authenticate();
            when(gameService.active(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_103"));
            mockMvc.perform(get(BASE + "/active").param("chatId", CHAT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedRuntime() throws Exception {
            authenticate();
            when(gameService.active(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/active").param("chatId", CHAT_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
