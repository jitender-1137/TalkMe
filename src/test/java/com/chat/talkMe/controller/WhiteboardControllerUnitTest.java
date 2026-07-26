package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.WhiteboardStrokeRequest;
import com.chat.talkMe.dto.response.WhiteboardOp;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WhiteboardService;
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

import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link WhiteboardController} — the Shared Whiteboard surface
 * (feature SHARED_WHITEBOARD): real-time collaborative drawing inside a 1:1 chat.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link WhiteboardService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>Every route is annotated {@code @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")},
 *       enforced by Spring's method-security interceptor (AOP) which is NOT active in a standalone
 *       MockMvc setup — the feature gate is covered by the integration test. The membership (IDOR)
 *       guard lives in {@code WhiteboardServiceImpl.requireChatMember} and is driven here by stubbing
 *       service exceptions (its direct unit coverage is in {@code WhiteboardServiceImplTest}).</li>
 *   <li>{@code POST /whiteboard/stroke} is the one route with {@code @Valid @RequestBody}, so it has a
 *       real bean-validation surface: a blank {@code chatUuid} ({@code @NotBlank}) surfaces as
 *       {@link org.springframework.web.bind.MethodArgumentNotValidException} → {@code VE_101}/400 via
 *       the handler. The {@code double size} primitive is subject to the Jackson-3 gotcha (missing →
 *       {@code 0.0}, never a parse error). The per-point {@code [x, y]} length bound is a service-side
 *       DoS check (not bean-validatable on a {@code List<double[]>}), so it surfaces here only as a
 *       service-thrown {@link BadRequestException} ({@code TM_821}).</li>
 * </ul>
 *
 * <p>Success codes are read straight from the controller: {@code getBoard} → {@code TM_000}
 * (via {@code success(data)}); {@code addStroke} → {@code TM_822}; {@code clear} → {@code TM_823};
 * {@code undo} → {@code TM_824}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WhiteboardController (unit)")
class WhiteboardControllerUnitTest {

    private static final String BASE = "/whiteboard";
    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private WhiteboardService whiteboardService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        WhiteboardController controller = new WhiteboardController(whiteboardService);

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

    private static WhiteboardOp strokeOp() {
        return WhiteboardOp.builder()
                .seq(7)
                .type("stroke")
                .authorUuid("author-uuid-1")
                .color("#ff0055")
                .size(4.0)
                .tool("pen")
                .points(List.of(new double[]{0.1, 0.2}, new double[]{0.3, 0.4}))
                .ts(1_700_000_000_000L)
                .build();
    }

    private static WhiteboardOp undoOp() {
        return WhiteboardOp.builder()
                .seq(8)
                .type("undo")
                .authorUuid("author-uuid-1")
                .ts(1_700_000_001_000L)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /whiteboard/{chatUuid}  (getBoard)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /whiteboard/{chatUuid}")
    class GetBoard {

        @Test
        void shouldReturn200WithOpLogAndForwardArgs() throws Exception {
            authenticate();
            when(whiteboardService.getBoard(any(), any())).thenReturn(List.of(strokeOp(), undoOp()));

            mockMvc.perform(get(BASE + "/" + CHAT_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // getBoard uses single-arg success(data) → generic code.
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].type").value("stroke"))
                    .andExpect(jsonPath("$.data[0].seq").value(7))
                    .andExpect(jsonPath("$.data[0].color").value("#ff0055"))
                    .andExpect(jsonPath("$.data[0].points[0][0]").value(0.1))
                    .andExpect(jsonPath("$.data[1].type").value("undo"));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(whiteboardService).getBoard(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_UUID);
            verify(whiteboardService, never()).addStroke(any(), any());
        }

        @Test
        void shouldReturn200WithEmptyBoard() throws Exception {
            authenticate();
            when(whiteboardService.getBoard(any(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/" + CHAT_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(whiteboardService).getBoard(testUser, CHAT_UUID);
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(whiteboardService.getBoard(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(get(BASE + "/" + CHAT_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenChatIdInvalid() throws Exception {
            authenticate();
            when(whiteboardService.getBoard(any(), any()))
                    .thenThrow(new BadRequestException("Invalid chat id", "TM_400"));

            mockMvc.perform(get(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(whiteboardService.getBoard(any(), any())).thenThrow(new RuntimeException("redis down"));

            mockMvc.perform(get(BASE + "/" + CHAT_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves null → userDetails.getUser()
            // NPEs inside the controller before the service is reached → catch-all 500.
            mockMvc.perform(get(BASE + "/" + CHAT_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(whiteboardService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /whiteboard/stroke  (addStroke, @Valid @RequestBody)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /whiteboard/stroke")
    class AddStroke {

        @Test
        void shouldReturn200AndForwardStrokeFields() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any())).thenReturn(strokeOp());

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\",\"color\":\"#ff0055\","
                                    + "\"size\":4.0,\"tool\":\"pen\","
                                    + "\"points\":[[0.1,0.2],[0.3,0.4]]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_822"))
                    .andExpect(jsonPath("$.message").value("Stroke added"))
                    .andExpect(jsonPath("$.data.type").value("stroke"))
                    .andExpect(jsonPath("$.data.seq").value(7));

            ArgumentCaptor<WhiteboardStrokeRequest> req =
                    ArgumentCaptor.forClass(WhiteboardStrokeRequest.class);
            verify(whiteboardService).addStroke(eq(testUser), req.capture());
            assertThat(req.getValue().getChatUuid()).isEqualTo(CHAT_UUID);
            assertThat(req.getValue().getColor()).isEqualTo("#ff0055");
            assertThat(req.getValue().getSize()).isEqualTo(4.0);
            assertThat(req.getValue().getTool()).isEqualTo("pen");
            assertThat(req.getValue().getPoints()).hasSize(2);
            assertThat(req.getValue().getPoints().get(0)).containsExactly(0.1, 0.2);
            verify(whiteboardService, never()).clear(any(), any());
        }

        @Test
        void shouldDefaultMissingSizePrimitiveToZeroNotError() throws Exception {
            authenticate();
            // Jackson-3 primitive gotcha: a missing `double size` deserializes to 0.0 (no error).
            when(whiteboardService.addStroke(any(), any())).thenReturn(strokeOp());

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<WhiteboardStrokeRequest> req =
                    ArgumentCaptor.forClass(WhiteboardStrokeRequest.class);
            verify(whiteboardService).addStroke(eq(testUser), req.capture());
            assertThat(req.getValue().getSize()).isEqualTo(0.0);
            assertThat(req.getValue().getColor()).isNull();
            assertThat(req.getValue().getTool()).isNull();
            assertThat(req.getValue().getPoints()).isNull();
        }

        @Test
        void shouldPassThroughUnicodeToolAndColorVerbatim() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any())).thenReturn(strokeOp());

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\",\"color\":\"#0f0\","
                                    + "\"tool\":\"pen🖊\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<WhiteboardStrokeRequest> req =
                    ArgumentCaptor.forClass(WhiteboardStrokeRequest.class);
            verify(whiteboardService).addStroke(eq(testUser), req.capture());
            // Controller does not sanitize; the raw string reaches the service.
            assertThat(req.getValue().getTool()).isEqualTo("pen🖊");
        }

        @Test
        void shouldReturn400WithVe101WhenChatUuidBlank() throws Exception {
            authenticate();
            // @NotBlank chatUuid missing → MethodArgumentNotValidException → VE_101/400.
            // Validation fails before the controller body runs, so the service is never called.
            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"color\":\"#fff\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("VE_101"));

            verifyNoInteractions(whiteboardService);
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsOversizeStroke() throws Exception {
            authenticate();
            // Service-side DoS bound (per-point / count) surfaces as BadRequest TM_821.
            when(whiteboardService.addStroke(any(), any()))
                    .thenThrow(new BadRequestException("A stroke may contain at most 2000 points", "TM_821"));

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\",\"points\":[[0.1,0.2]]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_821"));
        }

        @Test
        void shouldReturn409WhenServiceReportsConflict() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any()))
                    .thenThrow(new ConflictException("Conflict", "TM_409"));

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn403OnAccessDenied() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pinned).
            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(whiteboardService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // @RequestBody is required → missing body → HttpMessageNotReadableException → 500 (pinned).
            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(whiteboardService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(whiteboardService.addStroke(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/stroke").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatUuid\":\"" + CHAT_UUID + "\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /whiteboard/{chatUuid}/clear  (clear)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /whiteboard/{chatUuid}/clear")
    class Clear {

        @Test
        void shouldReturn200WithNullDataAndForwardArgs() throws Exception {
            authenticate();
            doNothing().when(whiteboardService).clear(any(), any());

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_823"))
                    .andExpect(jsonPath("$.message").value("Whiteboard cleared"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(whiteboardService).clear(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_UUID);
            verify(whiteboardService, never()).undo(any(), any());
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("You are not a member of this chat", "TM_103"))
                    .when(whiteboardService).clear(any(), any());

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/clear"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenChatIdInvalid() throws Exception {
            authenticate();
            doThrow(new BadRequestException("Invalid chat id", "TM_400"))
                    .when(whiteboardService).clear(any(), any());

            mockMvc.perform(post(BASE + "/not-a-uuid/clear"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(whiteboardService).clear(any(), any());

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/clear"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/clear"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(whiteboardService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /whiteboard/{chatUuid}/undo  (undo)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /whiteboard/{chatUuid}/undo")
    class Undo {

        @Test
        void shouldReturn200WithUndoOpAndForwardArgs() throws Exception {
            authenticate();
            when(whiteboardService.undo(any(), any())).thenReturn(undoOp());

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/undo"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_824"))
                    .andExpect(jsonPath("$.message").value("Undo broadcast"))
                    .andExpect(jsonPath("$.data.type").value("undo"))
                    .andExpect(jsonPath("$.data.seq").value(8));

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(whiteboardService).undo(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_UUID);
            verify(whiteboardService, never()).clear(any(), any());
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(whiteboardService.undo(any(), any()))
                    .thenThrow(new ForbiddenException("You are not a member of this chat", "TM_103"));

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/undo"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn404WhenServiceReportsNotFound() throws Exception {
            authenticate();
            when(whiteboardService.undo(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/undo"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(whiteboardService.undo(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + CHAT_UUID + "/undo"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
