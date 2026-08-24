package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AdviceQuestionPageResponse;
import com.neo.chat.dto.response.AdviceQuestionResponse;
import com.neo.chat.dto.response.AdviceReplyResponse;
import com.neo.chat.dto.response.AdviceThreadResponse;
import com.neo.chat.enums.AdviceCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.AdviceRoomService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link AdviceRoomController} (feature ADVICE_ROOMS).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link AdviceRoomService} and the real
 * {@link GlobalExceptionHandler}. The per-method {@code @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")}
 * and class-level {@code hasRole('USER')} are enforced by Spring method-security (inactive in a
 * standalone setup) and covered by integration tests; here we exercise wiring, request/response
 * mapping, validation, and exception translation.
 *
 * <p><b>Anonymity note:</b> the controller only serialises whatever the service maps. These tests
 * additionally pin that question/reply/thread payloads carry NO author identity on the wire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdviceRoomController (unit)")
class AdviceRoomControllerUnitTest {

    private static final String BASE = "/advice";
    private static final String Q_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String R_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String MODERATION_CODE = "TM_490";

    @Mock
    private AdviceRoomService adviceRoomService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        AdviceRoomController controller = new AdviceRoomController(adviceRoomService);

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
        testUser.setId(7L);
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

    private static AdviceQuestionResponse questionRes(AdviceCategory cat, String disclaimer, int replyCount) {
        return questionRes(cat, disclaimer, replyCount, false);
    }

    private static AdviceQuestionResponse questionRes(AdviceCategory cat, String disclaimer,
                                                      int replyCount, boolean mine) {
        return AdviceQuestionResponse.builder()
                .uuid(Q_UUID).title("How do I switch careers?").body("Advice please")
                .category(cat.name()).disclaimer(disclaimer).replyCount(replyCount).mine(mine)
                .createdAt("2026-08-01T10:00:00Z").build();
    }

    private static AdviceReplyResponse replyRes(String parentUuid) {
        return replyRes(parentUuid, false);
    }

    private static AdviceReplyResponse replyRes(String parentUuid, boolean mine) {
        return AdviceReplyResponse.builder()
                .uuid(R_UUID).body("Here is my take").parentReplyUuid(parentUuid).mine(mine)
                .createdAt("2026-08-01T11:00:00Z").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POST /advice/questions
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /questions")
    class Ask {

        @Test
        void shouldReturn200AndForwardArgumentsWhenValid() throws Exception {
            authenticate();
            when(adviceRoomService.askQuestion(any(), any()))
                    .thenReturn(questionRes(AdviceCategory.CAREER, "Peer opinions, not professional advice", 0));

            String body = """
                    {"title":"Career switch","body":"Should I move into tech?","category":"CAREER"}""";
            mockMvc.perform(post(BASE + "/questions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Question posted"))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.category").value("CAREER"))
                    .andExpect(jsonPath("$.data.disclaimer").value("Peer opinions, not professional advice"))
                    // No author identity on the wire.
                    .andExpect(jsonPath("$.data.author").doesNotExist())
                    .andExpect(jsonPath("$.data.username").doesNotExist());

            verify(adviceRoomService).askQuestion(eq(testUser), any());
        }

        @Test
        void shouldReturn400WhenTitleBlank() throws Exception {
            authenticate();
            String body = """
                    {"title":"   ","body":"Body"}""";
            mockMvc.perform(post(BASE + "/questions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(adviceRoomService);
        }

        @Test
        void shouldReturn400WhenBodyMissing() throws Exception {
            authenticate();
            String body = """
                    {"title":"A title"}""";
            mockMvc.perform(post(BASE + "/questions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(adviceRoomService);
        }

        @Test
        void shouldReturn422WhenModerationBlocks() throws Exception {
            authenticate();
            when(adviceRoomService.askQuestion(any(), any()))
                    .thenThrow(new ContentModerationException("blocked"));

            String body = """
                    {"title":"Title","body":"Body"}""";
            mockMvc.perform(post(BASE + "/questions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value(MODERATION_CODE));
        }

        @Test
        void shouldReturn429WhenRateCapped() throws Exception {
            authenticate();
            when(adviceRoomService.askQuestion(any(), any()))
                    .thenThrow(new TooManyRequestsException("slow down", "TM_851"));

            String body = """
                    {"title":"Title","body":"Body"}""";
            mockMvc.perform(post(BASE + "/questions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_851"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET /advice/questions
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /questions")
    class ListQuestions {

        @Test
        void shouldReturn200AndForwardCategoryCursorLimit() throws Exception {
            authenticate();
            AdviceQuestionPageResponse page = AdviceQuestionPageResponse.builder()
                    .items(List.of(questionRes(AdviceCategory.FINANCE, "Peer opinions, not professional advice", 2)))
                    .nextCursor("1").hasMore(true).build();
            when(adviceRoomService.listQuestions(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get(BASE + "/questions")
                            .param("category", "FINANCE").param("cursor", "0").param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].category").value("FINANCE"))
                    .andExpect(jsonPath("$.data.items[0].author").doesNotExist())
                    .andExpect(jsonPath("$.data.hasMore").value(true))
                    .andExpect(jsonPath("$.data.nextCursor").value("1"));

            ArgumentCaptor<AdviceCategory> cat = ArgumentCaptor.forClass(AdviceCategory.class);
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(adviceRoomService).listQuestions(eq(testUser), cat.capture(), cursor.capture(), limit.capture());
            assertThat(cat.getValue()).isEqualTo(AdviceCategory.FINANCE);
            assertThat(cursor.getValue()).isEqualTo("0");
            assertThat(limit.getValue()).isEqualTo(10);
        }

        @Test
        void shouldDefaultLimitAndAllowNoCategory() throws Exception {
            authenticate();
            when(adviceRoomService.listQuestions(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(AdviceQuestionPageResponse.builder().items(List.of()).hasMore(false).build());

            mockMvc.perform(get(BASE + "/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isEmpty());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(adviceRoomService).listQuestions(eq(testUser), eq(null), eq(null), limit.capture());
            assertThat(limit.getValue()).isEqualTo(20);
        }

        @Test
        void shouldExposeViewerRelativeMineFlagWithoutAuthorIdentity() throws Exception {
            authenticate();
            AdviceQuestionPageResponse page = AdviceQuestionPageResponse.builder()
                    .items(List.of(questionRes(AdviceCategory.LIFE, null, 0, true)))
                    .hasMore(false).build();
            when(adviceRoomService.listQuestions(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get(BASE + "/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].mine").value(true))
                    // mine never brings author identity onto the wire.
                    .andExpect(jsonPath("$.data.items[0].author").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].username").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].userUuid").doesNotExist());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET /advice/questions/{uuid}
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /questions/{uuid}")
    class GetQuestion {

        @Test
        void shouldReturn200WithThreadAndNoAuthorFields() throws Exception {
            authenticate();
            AdviceThreadResponse thread = AdviceThreadResponse.builder()
                    .question(questionRes(AdviceCategory.LIFE, null, 1, true))
                    .replies(List.of(replyRes(null, false))).build();
            when(adviceRoomService.getQuestion(any(), any())).thenReturn(thread);

            mockMvc.perform(get(BASE + "/questions/{uuid}", Q_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.question.uuid").value(Q_UUID))
                    .andExpect(jsonPath("$.data.question.author").doesNotExist())
                    // Viewer-relative mine flags are present, author identity is not.
                    .andExpect(jsonPath("$.data.question.mine").value(true))
                    .andExpect(jsonPath("$.data.replies[0].uuid").value(R_UUID))
                    .andExpect(jsonPath("$.data.replies[0].mine").value(false))
                    .andExpect(jsonPath("$.data.replies[0].author").doesNotExist())
                    .andExpect(jsonPath("$.data.replies[0].username").doesNotExist());

            verify(adviceRoomService).getQuestion(testUser, Q_UUID);
        }

        @Test
        void shouldReturn404WhenQuestionMissing() throws Exception {
            authenticate();
            when(adviceRoomService.getQuestion(any(), any()))
                    .thenThrow(new NotFoundException("Question not found", "TM_853"));

            mockMvc.perform(get(BASE + "/questions/{uuid}", Q_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_853"));
        }

        @Test
        void shouldReturn400WhenUuidMalformed() throws Exception {
            authenticate();
            when(adviceRoomService.getQuestion(any(), any()))
                    .thenThrow(new BadRequestException("Invalid id", "TM_852"));

            mockMvc.perform(get(BASE + "/questions/{uuid}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_852"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POST /advice/questions/{uuid}/replies
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /questions/{uuid}/replies")
    class Reply {

        @Test
        void shouldReturn200AndForwardArguments() throws Exception {
            authenticate();
            when(adviceRoomService.reply(any(), any(), any())).thenReturn(replyRes(null));

            String body = """
                    {"body":"My advice"}""";
            mockMvc.perform(post(BASE + "/questions/{uuid}/replies", Q_UUID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Reply posted"))
                    .andExpect(jsonPath("$.data.uuid").value(R_UUID))
                    .andExpect(jsonPath("$.data.author").doesNotExist());

            ArgumentCaptor<String> qUuid = ArgumentCaptor.forClass(String.class);
            verify(adviceRoomService).reply(eq(testUser), qUuid.capture(), any());
            assertThat(qUuid.getValue()).isEqualTo(Q_UUID);
        }

        @Test
        void shouldReturn400WhenBodyBlank() throws Exception {
            authenticate();
            String body = """
                    {"body":"   "}""";
            mockMvc.perform(post(BASE + "/questions/{uuid}/replies", Q_UUID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(adviceRoomService);
        }

        @Test
        void shouldReturn404WhenQuestionMissing() throws Exception {
            authenticate();
            when(adviceRoomService.reply(any(), any(), any()))
                    .thenThrow(new NotFoundException("Question not found", "TM_853"));

            String body = """
                    {"body":"My advice"}""";
            mockMvc.perform(post(BASE + "/questions/{uuid}/replies", Q_UUID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_853"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE /advice/questions/{uuid} and /advice/replies/{uuid}
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE question / reply")
    class Delete {

        @Test
        void shouldReturn200WhenDeletingOwnQuestion() throws Exception {
            authenticate();

            mockMvc.perform(delete(BASE + "/questions/{uuid}", Q_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Question deleted"))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE));

            verify(adviceRoomService).deleteMyQuestion(eq(testUser), eq(Q_UUID));
        }

        @Test
        void shouldReturn403WhenNotAuthorOfQuestion() throws Exception {
            authenticate();
            org.mockito.Mockito.doThrow(new ForbiddenException("Not your question", "TM_855"))
                    .when(adviceRoomService).deleteMyQuestion(any(), any());

            mockMvc.perform(delete(BASE + "/questions/{uuid}", Q_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_855"));
        }

        @Test
        void shouldReturn200WhenDeletingOwnReply() throws Exception {
            authenticate();

            mockMvc.perform(delete(BASE + "/replies/{uuid}", R_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Reply deleted"));

            verify(adviceRoomService).deleteMyReply(eq(testUser), eq(R_UUID));
        }

        @Test
        void shouldReturn403WhenNotAuthorOfReply() throws Exception {
            authenticate();
            org.mockito.Mockito.doThrow(new ForbiddenException("Not your reply", "TM_855"))
                    .when(adviceRoomService).deleteMyReply(any(), any());

            mockMvc.perform(delete(BASE + "/replies/{uuid}", R_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_855"));
        }
    }
}
