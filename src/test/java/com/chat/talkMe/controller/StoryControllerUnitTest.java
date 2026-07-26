package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.dto.response.StoryViewerResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StoryService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link StoryController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link StoryService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link AuthenticationPrincipalArgumentResolver}
 * for {@code @AuthenticationPrincipal}. No endpoint takes a {@code Pageable} and no
 * {@code @RequestBody} DTO has a primitive field, so neither the Pageable resolver nor the
 * tolerant Jackson converter is needed here.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} is enforced by Spring's
 * method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup — that
 * role gate, plus filter-chain authentication (JWT, CSRF), is out of scope here and covered by
 * the integration tests. This test verifies request/response wiring and delegation to the
 * service, which owns ownership/authorization checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoryController (unit)")
class StoryControllerUnitTest {

    private static final String BASE = "/stories";
    private static final String STORY_ID = "story-uuid-1";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private StoryService storyService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        StoryController controller = new StoryController(storyService);

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

    private static StoryResponse story(String id, String mediaUrl) {
        return StoryResponse.builder()
                .id(id).mediaUrl(mediaUrl).caption("A caption")
                .audience("EVERYONE").viewedByMe(false).build();
    }

    /** A story enriched with the owner-facing fields (view count / ownership / archive flag). */
    private static StoryResponse ownStory(String id, String mediaUrl, long viewCount, boolean expired) {
        return StoryResponse.builder()
                .id(id).mediaUrl(mediaUrl).caption("A caption")
                .audience("EVERYONE").viewedByMe(false)
                .viewCount(viewCount).owner(true).expired(expired).build();
    }

    private static StoryViewerResponse viewer(String id, String username) {
        return StoryViewerResponse.builder()
                .user(AuthUserResponse.builder().id(id).username(username).name("Viewer").build())
                .viewedAt("2026-07-25T10:15:30Z")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /stories  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /stories")
    class CreateStory {

        @Test
        void shouldReturn200AndForwardRequestWhenValid() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any())).thenReturn(story(STORY_ID, "https://cdn/x.png"));

            String body = """
                    {"mediaUrl":"https://cdn/x.png","caption":"hi","audience":"EVERYONE"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Story posted successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_230"))
                    .andExpect(jsonPath("$.data.id").value(STORY_ID))
                    .andExpect(jsonPath("$.data.mediaUrl").value("https://cdn/x.png"))
                    .andExpect(jsonPath("$.data.viewedByMe").value(false))
                    .andExpect(jsonPath("$.data.audience").value("EVERYONE"));

            ArgumentCaptor<StoryRequest> req = ArgumentCaptor.forClass(StoryRequest.class);
            verify(storyService).createStory(req.capture(), eq(testUser));
            assertThat(req.getValue().getMediaUrl()).isEqualTo("https://cdn/x.png");
            assertThat(req.getValue().getCaption()).isEqualTo("hi");
            assertThat(req.getValue().getAudience()).isEqualTo("EVERYONE");
        }

        @Test
        void shouldForwardCaptionAndAudienceForFriendsStory() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any())).thenReturn(story(STORY_ID, "https://cdn/y.png"));

            String body = """
                    {"mediaUrl":"https://cdn/y.png","caption":"only friends","audience":"FRIENDS"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            ArgumentCaptor<StoryRequest> req = ArgumentCaptor.forClass(StoryRequest.class);
            verify(storyService).createStory(req.capture(), any());
            assertThat(req.getValue().getCaption()).isEqualTo("only friends");
            assertThat(req.getValue().getAudience()).isEqualTo("FRIENDS");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenMediaUrlBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(storyService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenMediaUrlMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(storyService);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_101"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"https://cdn/x.png\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any()))
                    .thenThrow(new ForbiddenException("Not allowed to post a story", "TM_103"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"https://cdn/x.png\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenServiceConflicts() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any()))
                    .thenThrow(new ConflictException("Story already exists", "TM_231"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"https://cdn/x.png\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_231"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsAsBadRequest() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any()))
                    .thenThrow(new BadRequestException("Invalid media URL", "TM_071"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"https://cdn/x.png\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unreadable JSON has no dedicated handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(storyService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(storyService.createStory(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mediaUrl\":\"https://cdn/x.png\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /stories/active
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /stories/active")
    class GetActiveStories {

        @Test
        void shouldReturn200WithStoryList() throws Exception {
            authenticate();
            when(storyService.getActiveStories(any()))
                    .thenReturn(List.of(ownStory(STORY_ID, "https://cdn/x.png", 7, false)));

            mockMvc.perform(get(BASE + "/active"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].id").value(STORY_ID))
                    .andExpect(jsonPath("$.data[0].mediaUrl").value("https://cdn/x.png"))
                    .andExpect(jsonPath("$.data[0].viewCount").value(7))
                    .andExpect(jsonPath("$.data[0].owner").value(true))
                    .andExpect(jsonPath("$.data[0].expired").value(false));

            verify(storyService).getActiveStories(testUser);
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(storyService.getActiveStories(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/active"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(storyService.getActiveStories(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/active"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /stories/mine  (own archive, incl. expired)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /stories/mine")
    class GetMyStories {

        @Test
        void shouldReturn200WithArchiveIncludingExpiredAndViewCounts() throws Exception {
            authenticate();
            when(storyService.getMyStories(any())).thenReturn(List.of(
                    ownStory("active-1", "https://cdn/a.png", 12, false),
                    ownStory("expired-1", "https://cdn/b.png", 3, true)));

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].id").value("active-1"))
                    .andExpect(jsonPath("$.data[0].viewCount").value(12))
                    .andExpect(jsonPath("$.data[0].owner").value(true))
                    .andExpect(jsonPath("$.data[0].expired").value(false))
                    .andExpect(jsonPath("$.data[1].id").value("expired-1"))
                    .andExpect(jsonPath("$.data[1].viewCount").value(3))
                    .andExpect(jsonPath("$.data[1].expired").value(true));

            verify(storyService).getMyStories(testUser);
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(storyService.getMyStories(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(storyService.getMyStories(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/mine"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /stories/{id}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /stories/{id}")
    class DeleteStory {

        @Test
        void shouldReturn200AndForwardPathVarWhenDeleted() throws Exception {
            authenticate();
            doNothing().when(storyService).deleteStory(any(), any());

            mockMvc.perform(delete(BASE + "/" + STORY_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Story deleted successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_232"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
            verify(storyService).deleteStory(id.capture(), eq(testUser));
            assertThat(id.getValue()).isEqualTo(STORY_ID);
        }

        @Test
        void shouldReturn404WhenStoryNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Story not found", "TM_234"))
                    .when(storyService).deleteStory(any(), any());
            mockMvc.perform(delete(BASE + "/" + STORY_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_234"));
        }

        @Test
        void shouldReturn403WhenNotTheOwner() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Only the owner can delete this story", "TM_235"))
                    .when(storyService).deleteStory(any(), any());
            mockMvc.perform(delete(BASE + "/" + STORY_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_235"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /stories/{id}/view
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /stories/{id}/view")
    class ViewStory {

        @Test
        void shouldReturn200AndForwardPathVarWhenViewed() throws Exception {
            authenticate();
            doNothing().when(storyService).viewStory(any(), any());

            mockMvc.perform(post(BASE + "/" + STORY_ID + "/view"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Story viewed"))
                    .andExpect(jsonPath("$.messageCode").value("TM_233"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
            verify(storyService).viewStory(id.capture(), eq(testUser));
            assertThat(id.getValue()).isEqualTo(STORY_ID);
        }

        @Test
        void shouldReturn404WhenStoryNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Story not found", "TM_234"))
                    .when(storyService).viewStory(any(), any());
            mockMvc.perform(post(BASE + "/" + STORY_ID + "/view"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_234"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /stories/{id}/viewers
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /stories/{id}/viewers")
    class GetStoryViewers {

        @Test
        void shouldReturn200WithViewerList() throws Exception {
            authenticate();
            when(storyService.getStoryViewers(eq(STORY_ID), any()))
                    .thenReturn(List.of(viewer("viewer-1", "alice")));

            mockMvc.perform(get(BASE + "/" + STORY_ID + "/viewers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].user.id").value("viewer-1"))
                    .andExpect(jsonPath("$.data[0].user.username").value("alice"))
                    .andExpect(jsonPath("$.data[0].viewedAt").value("2026-07-25T10:15:30Z"));

            verify(storyService).getStoryViewers(STORY_ID, testUser);
        }

        @Test
        void shouldReturn200WithEmptyViewerList() throws Exception {
            authenticate();
            when(storyService.getStoryViewers(any(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/" + STORY_ID + "/viewers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn403WhenNotTheOwner() throws Exception {
            authenticate();
            when(storyService.getStoryViewers(any(), any()))
                    .thenThrow(new ForbiddenException("Only the owner can see viewers", "TM_235"));
            mockMvc.perform(get(BASE + "/" + STORY_ID + "/viewers"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_235"));
        }
    }
}
