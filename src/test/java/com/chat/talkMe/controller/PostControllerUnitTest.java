package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.PostResponse;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PostService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link PostController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link PostService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link PageableHandlerMethodArgumentResolver}
 * (feed/likes/comments/replies paginate) and {@link AuthenticationPrincipalArgumentResolver}.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} is enforced by
 * method-security (inactive in standalone MockMvc) — covered by the integration test. Ownership /
 * audience / moderation authorization lives in the service and is driven here by stubbed exceptions.
 *
 * <p>Note: {@code PostRequest} carries NO bean-validation constraints, so create/update accept any
 * JSON object (even {@code {}}) — content validation is a service concern. Only {@code PollVoteRequest}
 * ({@code optionId} @NotBlank) and {@code PostCommentRequest} ({@code content} @NotBlank) can produce
 * a VE_101 at the controller boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostController (unit)")
class PostControllerUnitTest {

    private static final String BASE = "/posts";
    private static final String PID = "post-uuid-1";
    private static final String CID = "comment-uuid-1";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";

    @Mock
    private PostService postService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        PostController controller = new PostController(postService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PageableHandlerMethodArgumentResolver())
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

    private static PostResponse samplePost(String id) {
        return PostResponse.builder()
                .id(id).shortCode("abc123").content("hello world")
                .user(AuthUserResponse.builder().id("u-1").username("testuser").build())
                .likesCount(3).commentsCount(1).build();
    }

    private static PostCommentResponse comment(String id) {
        return PostCommentResponse.builder()
                .id(id).userId("u-1").username("testuser").content("nice post").likesCount(2).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /posts  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /posts")
    class CreatePost {

        @Test
        void shouldReturn200AndForwardRequestWhenValid() throws Exception {
            authenticate();
            when(postService.createPost(any(), any())).thenReturn(samplePost(PID));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"hello world\",\"audience\":\"EVERYONE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_210"))
                    .andExpect(jsonPath("$.data.id").value(PID))
                    .andExpect(jsonPath("$.data.likesCount").value(3));

            ArgumentCaptor<PostRequest> req = ArgumentCaptor.forClass(PostRequest.class);
            verify(postService).createPost(req.capture(), eq(testUser));
            assertThat(req.getValue().getContent()).isEqualTo("hello world");
        }

        @Test
        void shouldAcceptEmptyBodyBecausePostRequestHasNoConstraints() throws Exception {
            authenticate();
            when(postService.createPost(any(), any())).thenReturn(samplePost(PID));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(postService).createPost(any(), eq(testUser));
        }

        @Test
        void shouldReturn422WhenModerationBlocksContent() throws Exception {
            authenticate();
            when(postService.createPost(any(), any()))
                    .thenThrow(new ServiceException(422, "Post blocked by moderation", "TM_211"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"bad\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value("TM_211"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(postService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(postService.createPost(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"x\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /posts/{id}/poll/vote
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /posts/{id}/poll/vote")
    class VotePoll {

        @Test
        void shouldReturn200AndForwardOptionId() throws Exception {
            authenticate();
            when(postService.votePoll(eq(PID), eq("opt-1"), any())).thenReturn(samplePost(PID));

            mockMvc.perform(post(BASE + "/" + PID + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":\"opt-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_229"));

            verify(postService).votePoll(PID, "opt-1", testUser);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenOptionIdBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + PID + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(postService);
        }

        @Test
        void shouldReturn400WhenOptionIdMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + PID + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(postService);
        }

        @Test
        void shouldReturn404WhenPostNotFound() throws Exception {
            authenticate();
            when(postService.votePoll(any(), any(), any()))
                    .thenThrow(new NotFoundException("Post not found", "TM_212"));
            mockMvc.perform(post(BASE + "/" + PID + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":\"opt-1\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }

        @Test
        void shouldReturn409WhenAlreadyVoted() throws Exception {
            authenticate();
            when(postService.votePoll(any(), any(), any()))
                    .thenThrow(new ConflictException("Already voted", "TM_230"));
            mockMvc.perform(post(BASE + "/" + PID + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":\"opt-1\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_230"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /posts/{id}  &  GET /posts/by-code/{code}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET single post")
    class GetPost {

        @Test
        void shouldReturn200WithPost() throws Exception {
            authenticate();
            when(postService.getPost(eq(PID), any())).thenReturn(samplePost(PID));

            mockMvc.perform(get(BASE + "/" + PID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(PID))
                    .andExpect(jsonPath("$.data.shortCode").value("abc123"));

            verify(postService).getPost(PID, testUser);
        }

        @Test
        void shouldReturn404WhenPostNotFound() throws Exception {
            authenticate();
            when(postService.getPost(any(), any()))
                    .thenThrow(new NotFoundException("Post not found", "TM_212"));
            mockMvc.perform(get(BASE + "/" + PID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }

        @Test
        void shouldReturn403WhenAudienceRestricted() throws Exception {
            authenticate();
            when(postService.getPost(any(), any()))
                    .thenThrow(new ForbiddenException("This post is for friends only", "TM_225"));
            mockMvc.perform(get(BASE + "/" + PID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_225"));
        }

        @Test
        void shouldReturn200WhenFetchedByShortCode() throws Exception {
            authenticate();
            when(postService.getPostByShortCode(eq("abc123"), any())).thenReturn(samplePost(PID));

            mockMvc.perform(get(BASE + "/by-code/abc123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(PID));

            verify(postService).getPostByShortCode("abc123", testUser);
        }

        @Test
        void shouldReturn404WhenShortCodeUnknown() throws Exception {
            authenticate();
            when(postService.getPostByShortCode(any(), any()))
                    .thenThrow(new NotFoundException("Post not found", "TM_212"));
            mockMvc.perform(get(BASE + "/by-code/zzz"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /posts/{id}  (update)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /posts/{id}")
    class UpdatePost {

        @Test
        void shouldReturn200WhenUpdated() throws Exception {
            authenticate();
            when(postService.updatePost(eq(PID), any(), any())).thenReturn(samplePost(PID));

            mockMvc.perform(put(BASE + "/" + PID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"edited\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_214"));

            verify(postService).updatePost(eq(PID), any(), eq(testUser));
        }

        @Test
        void shouldReturn403WhenNotOwner() throws Exception {
            authenticate();
            when(postService.updatePost(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Only the author can edit", "TM_226"));
            mockMvc.perform(put(BASE + "/" + PID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_226"));
        }

        @Test
        void shouldReturn404WhenPostMissing() throws Exception {
            authenticate();
            when(postService.updatePost(any(), any(), any()))
                    .thenThrow(new NotFoundException("Post not found", "TM_212"));
            mockMvc.perform(put(BASE + "/" + PID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"x\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Feeds (paginated)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET feeds")
    class Feeds {

        @Test
        void shouldReturn200WithHomeFeed() throws Exception {
            authenticate();
            when(postService.getFeed(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(samplePost(PID)), PageRequest.of(0, 20), 1));

            mockMvc.perform(get(BASE + "/feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(PID));

            verify(postService).getFeed(any(Pageable.class), eq(testUser));
        }

        @Test
        void shouldApplyFeedDefaultPageSizeAndSort() throws Exception {
            authenticate();
            when(postService.getFeed(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/feed")).andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getFeed(pageable.capture(), any());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void shouldHonorCustomFeedPageAndSize() throws Exception {
            authenticate();
            when(postService.getFeed(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 5), 0));

            mockMvc.perform(get(BASE + "/feed").param("page", "3").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getFeed(pageable.capture(), any());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        @Test
        void shouldReturn200WithProfileFeedForwardingUserUuid() throws Exception {
            authenticate();
            when(postService.getProfileFeed(eq("user-9"), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(samplePost(PID)), PageRequest.of(0, 20), 1));

            mockMvc.perform(get(BASE + "/user/user-9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(PID));

            verify(postService).getProfileFeed(eq("user-9"), any(Pageable.class), eq(testUser));
        }

        @Test
        void shouldReturn404WhenProfileUserMissing() throws Exception {
            authenticate();
            when(postService.getProfileFeed(any(), any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));
            mockMvc.perform(get(BASE + "/user/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /posts/{id}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /posts/{id}")
    class DeletePost {

        @Test
        void shouldReturn200WhenDeleted() throws Exception {
            authenticate();
            doNothing().when(postService).deletePost(any(), any());

            mockMvc.perform(delete(BASE + "/" + PID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_213"));

            verify(postService).deletePost(PID, testUser);
        }

        @Test
        void shouldReturn403WhenNotOwner() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Only the author can delete", "TM_226"))
                    .when(postService).deletePost(any(), any());
            mockMvc.perform(delete(BASE + "/" + PID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_226"));
        }

        @Test
        void shouldReturn404WhenMissing() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Post not found", "TM_212"))
                    .when(postService).deletePost(any(), any());
            mockMvc.perform(delete(BASE + "/" + PID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Likes / bookmarks (post-level)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Post like / bookmark")
    class LikeBookmark {

        @Test
        void shouldLikePost() throws Exception {
            authenticate();
            doNothing().when(postService).likePost(any(), any());

            mockMvc.perform(post(BASE + "/" + PID + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_214"));

            verify(postService).likePost(PID, testUser);
            verify(postService, never()).unlikePost(any(), any());
        }

        @Test
        void shouldUnlikePost() throws Exception {
            authenticate();
            doNothing().when(postService).unlikePost(any(), any());

            mockMvc.perform(delete(BASE + "/" + PID + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_215"));

            verify(postService).unlikePost(PID, testUser);
        }

        @Test
        void shouldReturn404WhenLikingMissingPost() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Post not found", "TM_212"))
                    .when(postService).likePost(any(), any());
            mockMvc.perform(post(BASE + "/" + PID + "/like"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }

        @Test
        void shouldReturn200WithLikesPage() throws Exception {
            authenticate();
            when(postService.getPostLikes(eq(PID), any(), any())).thenReturn(
                    new PageImpl<>(List.of(AuthUserResponse.builder().id("u-2").username("liker").build()),
                            PageRequest.of(0, 30), 1));

            mockMvc.perform(get(BASE + "/" + PID + "/likes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].username").value("liker"));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getPostLikes(eq(PID), pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageSize()).isEqualTo(30);
        }

        @Test
        void shouldBookmarkPost() throws Exception {
            authenticate();
            doNothing().when(postService).bookmarkPost(any(), any());

            mockMvc.perform(post(BASE + "/" + PID + "/bookmark"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_216"));

            verify(postService).bookmarkPost(PID, testUser);
            verify(postService, never()).unbookmarkPost(any(), any());
        }

        @Test
        void shouldUnbookmarkPost() throws Exception {
            authenticate();
            doNothing().when(postService).unbookmarkPost(any(), any());

            mockMvc.perform(delete(BASE + "/" + PID + "/bookmark"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_217"));

            verify(postService).unbookmarkPost(PID, testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Comments
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Comments")
    class Comments {

        @Test
        void shouldAddCommentForwardingContent() throws Exception {
            authenticate();
            when(postService.addComment(eq(PID), any(), any())).thenReturn(comment(CID));

            mockMvc.perform(post(BASE + "/" + PID + "/comments").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"nice post\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_219"))
                    .andExpect(jsonPath("$.data.id").value(CID));

            ArgumentCaptor<PostCommentRequest> req = ArgumentCaptor.forClass(PostCommentRequest.class);
            verify(postService).addComment(eq(PID), req.capture(), eq(testUser));
            assertThat(req.getValue().getContent()).isEqualTo("nice post");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenCommentBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + PID + "/comments").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(postService);
        }

        @Test
        void shouldReturn404WhenCommentingOnMissingPost() throws Exception {
            authenticate();
            when(postService.addComment(any(), any(), any()))
                    .thenThrow(new NotFoundException("Post not found", "TM_212"));
            mockMvc.perform(post(BASE + "/" + PID + "/comments").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"hi\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_212"));
        }

        @Test
        void shouldReturn200WithCommentsPage() throws Exception {
            authenticate();
            when(postService.getComments(eq(PID), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(comment(CID)), PageRequest.of(0, 15), 1));

            mockMvc.perform(get(BASE + "/" + PID + "/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(CID));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getComments(eq(PID), pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageSize()).isEqualTo(15);
        }

        @Test
        void shouldReturn200WithRepliesPageSortedAscending() throws Exception {
            authenticate();
            when(postService.getReplies(eq(PID), eq(CID), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(comment("reply-1")), PageRequest.of(0, 10), 1));

            mockMvc.perform(get(BASE + "/" + PID + "/comments/" + CID + "/replies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value("reply-1"));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getReplies(eq(PID), eq(CID), pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void shouldEditComment() throws Exception {
            authenticate();
            when(postService.editComment(eq(PID), eq(CID), any(), any())).thenReturn(comment(CID));

            mockMvc.perform(put(BASE + "/" + PID + "/comments/" + CID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"edited\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_222"));

            verify(postService).editComment(eq(PID), eq(CID), any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenEditingWithBlankContent() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE + "/" + PID + "/comments/" + CID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(postService);
        }

        @Test
        void shouldReturn403WhenEditingSomeoneElsesComment() throws Exception {
            authenticate();
            when(postService.editComment(any(), any(), any(), any()))
                    .thenThrow(new ForbiddenException("Only the author can edit", "TM_226"));
            mockMvc.perform(put(BASE + "/" + PID + "/comments/" + CID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_226"));
        }

        @Test
        void shouldDeleteComment() throws Exception {
            authenticate();
            doNothing().when(postService).deleteComment(any(), any(), any());

            mockMvc.perform(delete(BASE + "/" + PID + "/comments/" + CID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_220"));

            verify(postService).deleteComment(PID, CID, testUser);
        }

        @Test
        void shouldReturn404WhenDeletingMissingComment() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Comment not found", "TM_221"))
                    .when(postService).deleteComment(any(), any(), any());
            mockMvc.perform(delete(BASE + "/" + PID + "/comments/" + CID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_221"));
        }

        @Test
        void shouldLikeComment() throws Exception {
            authenticate();
            doNothing().when(postService).likeComment(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + PID + "/comments/" + CID + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_223"));

            verify(postService).likeComment(PID, CID, testUser);
            verify(postService, never()).unlikeComment(any(), any(), any());
        }

        @Test
        void shouldUnlikeComment() throws Exception {
            authenticate();
            doNothing().when(postService).unlikeComment(any(), any(), any());

            mockMvc.perform(delete(BASE + "/" + PID + "/comments/" + CID + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_224"));

            verify(postService).unlikeComment(PID, CID, testUser);
        }
    }
}
