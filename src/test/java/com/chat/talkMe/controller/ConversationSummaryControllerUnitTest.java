package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConversationSummaryResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ConversationSummaryService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ConversationSummaryController} — the "Our Story"
 * read-only 1:1 conversation summary (feature #3.3).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ConversationSummaryService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>{@code @PreAuthorize("@featureGuard.check('CONVERSATION_SUMMARY')")} is enforced by
 *       Spring's method-security interceptor (AOP), which is NOT active in a standalone MockMvc
 *       setup — the CONVERSATION_SUMMARY entitlement gate is covered by the integration test. Here
 *       we verify request/response wiring and delegation to the service (which owns the
 *       membership/IDOR guard, the 1:1-only rule and the count arithmetic).</li>
 *   <li>The single endpoint has NO {@code @RequestBody} and NO {@code @RequestParam}: it takes only
 *       a {@code String} {@code @PathVariable chatUuid} and the authenticated principal. There is
 *       therefore no bean-validation surface ({@code VE_101}), no missing-param 500 path and no
 *       malformed-JSON 500 path to exercise. A {@link LocalValidatorFactoryBean} is still wired for
 *       parity with the repo template.</li>
 *   <li>The controller forwards the raw path {@code chatUuid} straight to the service without a
 *       repository lookup, so UUID validity / not-found / not-a-member are all decided by the
 *       service and are driven here by stubbing service exceptions.</li>
 * </ul>
 *
 * <p>The endpoint emits success code {@code TM_000} directly from the controller
 * ({@code SuccessResponseDto.success(data)} → message "Success").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSummaryController (unit)")
class ConversationSummaryControllerUnitTest {

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String BASE = "/chats/" + CHAT_UUID + "/summary";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private ConversationSummaryService conversationSummaryService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ConversationSummaryController controller =
                new ConversationSummaryController(conversationSummaryService);

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
        testUser.setId(1L);
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

    /** A fully-populated "Our Story" card for a 1:1 chat with Bob. */
    private static ConversationSummaryResponse fullSummary() {
        return ConversationSummaryResponse.builder()
                .chatUuid(CHAT_UUID)
                .otherName("Bob")
                .otherUsername("bob")
                .otherAvatar("https://cdn.example.com/bob.png")
                .totalMessages(42)
                .myMessages(25)
                .theirMessages(17)
                .photosShared(5)
                .firstMessageAt(Instant.parse("2026-01-01T00:00:00Z"))
                .daysKnown(120)
                .activeDays(30)
                .sharedInterests(List.of("Gaming", "Travel"))
                .headline("You and Bob have exchanged 42 messages over 120 days. "
                        + "Shared 5 photos. You both love Gaming and Travel.")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatUuid}/summary
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats/{chatUuid}/summary")
    class Summary {

        @Test
        void shouldReturn200WithFullSummaryAndForwardUserAndChatUuid() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any())).thenReturn(fullSummary());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.chatUuid").value(CHAT_UUID))
                    .andExpect(jsonPath("$.data.otherName").value("Bob"))
                    .andExpect(jsonPath("$.data.otherUsername").value("bob"))
                    .andExpect(jsonPath("$.data.otherAvatar").value("https://cdn.example.com/bob.png"))
                    .andExpect(jsonPath("$.data.totalMessages").value(42))
                    .andExpect(jsonPath("$.data.myMessages").value(25))
                    .andExpect(jsonPath("$.data.theirMessages").value(17))
                    .andExpect(jsonPath("$.data.photosShared").value(5))
                    .andExpect(jsonPath("$.data.daysKnown").value(120))
                    .andExpect(jsonPath("$.data.activeDays").value(30))
                    .andExpect(jsonPath("$.data.sharedInterests").isArray())
                    .andExpect(jsonPath("$.data.sharedInterests.length()").value(2))
                    .andExpect(jsonPath("$.data.sharedInterests[0]").value("Gaming"))
                    .andExpect(jsonPath("$.data.sharedInterests[1]").value("Travel"))
                    .andExpect(jsonPath("$.data.headline").isNotEmpty());

            // The authenticated principal's User and the raw path chatUuid both reach the service.
            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(conversationSummaryService).summarize(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(CHAT_UUID);
        }

        @Test
        void shouldForwardRawPathChatUuidVerbatim() throws Exception {
            authenticate();
            // The controller does not parse/validate the path — it forwards the decoded segment
            // straight to the service. A non-UUID segment must pass through untouched.
            String weird = "not-a-uuid-42";
            when(conversationSummaryService.summarize(any(), any())).thenReturn(fullSummary());

            mockMvc.perform(get("/chats/{chatUuid}/summary", weird))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> chatUuid = ArgumentCaptor.forClass(String.class);
            verify(conversationSummaryService).summarize(eq(testUser), chatUuid.capture());
            assertThat(chatUuid.getValue()).isEqualTo(weird);
        }

        @Test
        void shouldPassThroughUnicodeAndInjectionTextInHeadlineVerbatim() throws Exception {
            authenticate();
            // Free-form generated headline carries unicode/emoji + XSS/SQLi; the controller must
            // serialize it verbatim (no escaping/stripping at this layer).
            String hostile = "You & Bøb 🎉 <script>alert('xss')</script>'; DROP TABLE chats;--";
            when(conversationSummaryService.summarize(any(), any())).thenReturn(
                    ConversationSummaryResponse.builder()
                            .chatUuid(CHAT_UUID).headline(hostile).sharedInterests(List.of()).build());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.headline").value(hostile));
        }

        @Test
        void shouldReturn200WithMinimalSummaryWhenOtherNotResolved() throws Exception {
            authenticate();
            // Empty/one-sided chat: no other participant resolved, so the header fields are null and
            // Jackson omits them; numeric primitives still serialize (0) and the headline is present.
            when(conversationSummaryService.summarize(any(), any())).thenReturn(
                    ConversationSummaryResponse.builder()
                            .chatUuid(CHAT_UUID)
                            .totalMessages(0).myMessages(0).theirMessages(0)
                            .sharedInterests(List.of())
                            .headline("Your story with them is just getting started.")
                            .build());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.otherName").doesNotExist())
                    .andExpect(jsonPath("$.data.otherUsername").doesNotExist())
                    .andExpect(jsonPath("$.data.totalMessages").value(0))
                    .andExpect(jsonPath("$.data.sharedInterests").isEmpty())
                    .andExpect(jsonPath("$.data.headline").value("Your story with them is just getting started."));
        }

        @Test
        void shouldReturn403WhenCallerNotAMember() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new ForbiddenException("You are not part of this conversation", "TM_026"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));
        }

        @Test
        void shouldReturn403WhenChatIsMultiParty() throws Exception {
            authenticate();
            // Same 403/TM_026 the service raises when the chat is a group/room (1:1-only rule).
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new ForbiddenException("Summaries are only for 1:1 chats", "TM_026"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_024"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsRequest() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new BadRequestException("Bad summary request", "TM_070"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_070"));
        }

        @Test
        void shouldReturn403WithTm005OnAccessDenied() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn400WithTm071OnIllegalArgument() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad argument"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // No SecurityContext → @AuthenticationPrincipal resolves null → userDetails.getUser()
            // NPEs inside the controller before the service is reached → catch-all 500.
            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(conversationSummaryService);
        }

        @Test
        void shouldNotInvokeServiceMoreThanOncePerRequest() throws Exception {
            authenticate();
            when(conversationSummaryService.summarize(any(), any())).thenReturn(fullSummary());

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            verify(conversationSummaryService).summarize(eq(testUser), eq(CHAT_UUID));
            verify(conversationSummaryService, never()).summarize(eq(testUser), eq("other"));
        }
    }
}
