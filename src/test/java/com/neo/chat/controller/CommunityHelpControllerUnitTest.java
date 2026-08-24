package com.neo.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.AnswerRequest;
import com.neo.chat.dto.request.PostHelpRequest;
import com.neo.chat.dto.response.HelpAnswerResponse;
import com.neo.chat.dto.response.HelpFeedResponse;
import com.neo.chat.dto.response.HelpRequestResponse;
import com.neo.chat.dto.response.HelpUserInfo;
import com.neo.chat.enums.HelpCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.CommunityHelpService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link CommunityHelpController} (feature #9, COMMUNITY_HELP).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link CommunityHelpService} and the real
 * {@link GlobalExceptionHandler}. Method-security ({@code hasRole('USER')} and the
 * {@code @featureGuard.check('COMMUNITY_HELP')} gate) is inactive in standalone MockMvc and is
 * covered by the integration test. Success paths emit {@code TM_000}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityHelpController (unit)")
class CommunityHelpControllerUnitTest {

    private static final String BASE = "/community-help";
    private static final String REQ_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";

    @Mock
    private CommunityHelpService communityHelpService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private User testUser;

    @BeforeEach
    void setUp() {
        CommunityHelpController controller = new CommunityHelpController(communityHelpService);

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

    private static HelpUserInfo askerInfo() {
        return HelpUserInfo.builder()
                .userUuid("asker-uuid").name("Alice").username("alice")
                .avatar("https://cdn/alice.jpg").city("Pune").presence("ONLINE")
                .build();
    }

    private static HelpRequestResponse requestResponse() {
        return HelpRequestResponse.builder()
                .uuid(REQ_ID).city("Pune").category("TRANSPORT")
                .body("Is the metro running?").status("OPEN").answerCount(0)
                .expiresAt("2026-08-23T18:00:00Z").createdAt("2026-08-23T12:00:00Z")
                .asker(askerInfo())
                .build();
    }

    private static HelpAnswerResponse answerResponse() {
        return HelpAnswerResponse.builder()
                .uuid("answer-uuid").requestUuid(REQ_ID).body("Take the 42 bus")
                .createdAt("2026-08-23T12:05:00Z")
                .answerer(HelpUserInfo.builder().username("bob").name("Bob").presence("AWAY").build())
                .build();
    }

    // ── POST /community-help ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /community-help")
    class Post {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            when(communityHelpService.postHelp(any(), any(), any(), any())).thenReturn(requestResponse());

            PostHelpRequest body = PostHelpRequest.builder()
                    .city("Pune").category(HelpCategory.TRANSPORT).body("Is the metro running?").build();

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.city").value("Pune"))
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andExpect(jsonPath("$.data.asker.username").value("alice"));

            verify(communityHelpService).postHelp(eq(testUser), eq("Pune"),
                    eq(HelpCategory.TRANSPORT), eq("Is the metro running?"));
        }

        @Test
        void shouldReturn400OnBlankBody() throws Exception {
            authenticate();
            PostHelpRequest body = PostHelpRequest.builder().city("Pune").body("   ").build();

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verify(communityHelpService, never()).postHelp(any(), any(), any(), any());
        }

        @Test
        void shouldMap429FromServiceCap() throws Exception {
            authenticate();
            when(communityHelpService.postHelp(any(), any(), any(), any()))
                    .thenThrow(new TooManyRequestsException("too many", "TM_986"));

            PostHelpRequest body = PostHelpRequest.builder().city("Pune").body("Help?").build();

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_986"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(communityHelpService.postHelp(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            PostHelpRequest body = PostHelpRequest.builder().city("Pune").body("Help?").build();

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ── GET /community-help/feed ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /community-help/feed")
    class Feed {

        @Test
        void shouldReturn200WithFeed() throws Exception {
            authenticate();
            HelpFeedResponse feed = HelpFeedResponse.builder()
                    .items(List.of(requestResponse())).nextCursor("1").hasMore(true).build();
            when(communityHelpService.feed(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(feed);

            mockMvc.perform(get(BASE + "/feed").param("city", "Pune").param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].body").value("Is the metro running?"))
                    .andExpect(jsonPath("$.data.hasMore").value(true))
                    .andExpect(jsonPath("$.data.nextCursor").value("1"));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(communityHelpService).feed(eq(testUser), eq("Pune"), isNull(), isNull(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(20);
        }

        @Test
        void shouldForwardCategoryFilterAndDefaultLimit() throws Exception {
            authenticate();
            when(communityHelpService.feed(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(HelpFeedResponse.builder().items(List.of()).hasMore(false).build());

            mockMvc.perform(get(BASE + "/feed").param("category", "SAFETY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasMore").value(false));

            verify(communityHelpService).feed(eq(testUser), isNull(), eq(HelpCategory.SAFETY), isNull(), eq(0));
        }
    }

    // ── POST /community-help/{uuid}/answer ────────────────────────────────────────────

    @Nested
    @DisplayName("POST /community-help/{uuid}/answer")
    class Answer {

        @Test
        void shouldReturn200AndForward() throws Exception {
            authenticate();
            when(communityHelpService.answer(any(), any(), any())).thenReturn(answerResponse());

            AnswerRequest body = AnswerRequest.builder().body("Take the 42 bus").build();

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.body").value("Take the 42 bus"))
                    .andExpect(jsonPath("$.data.requestUuid").value(REQ_ID));

            verify(communityHelpService).answer(testUser, REQ_ID, "Take the 42 bus");
        }

        @Test
        void shouldReturn400OnBlankAnswer() throws Exception {
            authenticate();
            AnswerRequest body = AnswerRequest.builder().body("  ").build();

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));

            verify(communityHelpService, never()).answer(any(), any(), any());
        }

        @Test
        void shouldMap404FromService() throws Exception {
            authenticate();
            when(communityHelpService.answer(any(), any(), any()))
                    .thenThrow(new NotFoundException("gone", "TM_988"));

            AnswerRequest body = AnswerRequest.builder().body("hi").build();

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_988"));
        }
    }

    // ── GET /community-help/{uuid}/answers ────────────────────────────────────────────

    @Nested
    @DisplayName("GET /community-help/{uuid}/answers")
    class Answers {

        @Test
        void shouldReturn200WithAnswers() throws Exception {
            authenticate();
            when(communityHelpService.getAnswers(any(), any()))
                    .thenReturn(List.of(answerResponse()));

            mockMvc.perform(get(BASE + "/" + REQ_ID + "/answers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].body").value("Take the 42 bus"))
                    .andExpect(jsonPath("$.data[0].requestUuid").value(REQ_ID))
                    .andExpect(jsonPath("$.data[0].answerer.username").value("bob"));

            verify(communityHelpService).getAnswers(testUser, REQ_ID);
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(communityHelpService.getAnswers(any(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/" + REQ_ID + "/answers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(communityHelpService).getAnswers(testUser, REQ_ID);
        }

        @Test
        void shouldMap404FromService() throws Exception {
            authenticate();
            when(communityHelpService.getAnswers(any(), any()))
                    .thenThrow(new NotFoundException("gone", "TM_988"));

            mockMvc.perform(get(BASE + "/" + REQ_ID + "/answers"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_988"));
        }
    }

    // ── POST /community-help/{uuid}/resolve ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /community-help/{uuid}/resolve")
    class Resolve {

        @Test
        void shouldReturn200() throws Exception {
            authenticate();
            HelpRequestResponse resolved = requestResponse();
            resolved.setStatus("RESOLVED");
            when(communityHelpService.markResolved(any(), any())).thenReturn(resolved);

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/resolve"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.status").value("RESOLVED"));

            verify(communityHelpService).markResolved(testUser, REQ_ID);
        }

        @Test
        void shouldMap403WhenNotAsker() throws Exception {
            authenticate();
            when(communityHelpService.markResolved(any(), any()))
                    .thenThrow(new ForbiddenException("only the asker", "TM_989"));

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/resolve"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_989"));
        }

        @Test
        void shouldMap400OnBadRequestFromService() throws Exception {
            authenticate();
            when(communityHelpService.markResolved(any(), any()))
                    .thenThrow(new BadRequestException("bad id", "TM_983"));

            mockMvc.perform(post(BASE + "/" + REQ_ID + "/resolve"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_983"));
        }
    }
}
