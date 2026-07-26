package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FollowService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link FollowController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FollowService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link PageableHandlerMethodArgumentResolver}
 * (needed for the {@code /followers} &amp; {@code /following} list endpoints' {@code Pageable})
 * and {@link AuthenticationPrincipalArgumentResolver} for {@code @AuthenticationPrincipal}.
 *
 * <p>No endpoint here accepts a {@code @RequestBody}: every mutating call is driven purely by a
 * path variable, so there is no request DTO to validate and no tolerant Jackson converter is
 * required. All authorization/ownership rules live in the service and are driven here by stubbing
 * service exceptions.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} is enforced
 * by Spring's method-security interceptor, which is inactive in standalone MockMvc — covered by
 * the integration test. Filter-chain authentication (JWT, CSRF, roles) is likewise out of scope.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowController (unit)")
class FollowControllerUnitTest {

    private static final String BASE = "/follows";
    private static final String TARGET_ID = "user-uuid-2";
    private static final String FOLLOWER_ID = "follower-uuid-3";
    private static final UUID ME_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private FollowService followService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FollowController controller = new FollowController(followService);

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
        // uuid lives on BaseEntity (plain @Builder on User doesn't surface it); set via @Setter so
        // the controller's "me" → own-uuid resolution has a value to read.
        testUser.setUuid(ME_UUID);
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

    private static AuthUserResponse user(String username) {
        return AuthUserResponse.builder().id("u-1").username(username).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /follows/{userUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /follows/{userUuid} (follow)")
    class FollowUser {

        @Test
        void shouldReturn200AndForwardTargetAndUser() throws Exception {
            authenticate();
            doNothing().when(followService).followUser(any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_254"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
            verify(followService).followUser(target.capture(), eq(testUser));
            assertThat(target.getValue()).isEqualTo(TARGET_ID);
            verify(followService, never()).unfollowUser(any(), any());
        }

        @Test
        void shouldReturn404WhenTargetNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("User not found", "TM_250"))
                    .when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_250"));
        }

        @Test
        void shouldReturn409WhenAlreadyFollowing() throws Exception {
            authenticate();
            doThrow(new ConflictException("Already following this user", "TM_251"))
                    .when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_251"));
        }

        @Test
        void shouldReturn400WhenFollowingSelf() throws Exception {
            authenticate();
            doThrow(new BadRequestException("Cannot follow yourself", "TM_252"))
                    .when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/self"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_252"));
        }

        @Test
        void shouldReturn403WhenTargetHasBlockedRequester() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Cannot follow this user", "TM_253"))
                    .when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_253"));
        }

        @Test
        void shouldReturn400WithInvalidUuidCodeWhenUuidUnparseable() throws Exception {
            authenticate();
            // GlobalExceptionHandler maps IllegalArgumentException whose message contains
            // "Invalid UUID string" to TM_INVALID_UUID (otherwise TM_071).
            doThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"))
                    .when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(followService).followUser(any(), any());
            mockMvc.perform(post(BASE + "/" + TARGET_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /follows/{userUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /follows/{userUuid} (unfollow)")
    class UnfollowUser {

        @Test
        void shouldReturn200AndForwardTargetAndUser() throws Exception {
            authenticate();
            doNothing().when(followService).unfollowUser(any(), any());

            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_255"));

            verify(followService).unfollowUser(TARGET_ID, testUser);
            verify(followService, never()).followUser(any(), any());
        }

        @Test
        void shouldReturn404WhenNotFollowing() throws Exception {
            authenticate();
            doThrow(new NotFoundException("You are not following this user", "TM_257"))
                    .when(followService).unfollowUser(any(), any());
            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_257"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /follows/followers/{followerUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /follows/followers/{followerUuid} (remove follower)")
    class RemoveFollower {

        @Test
        void shouldReturn200AndForwardFollowerAndUser() throws Exception {
            authenticate();
            doNothing().when(followService).removeFollower(any(), any());

            mockMvc.perform(delete(BASE + "/followers/" + FOLLOWER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_256"));

            ArgumentCaptor<String> follower = ArgumentCaptor.forClass(String.class);
            verify(followService).removeFollower(follower.capture(), eq(testUser));
            assertThat(follower.getValue()).isEqualTo(FOLLOWER_ID);
        }

        @Test
        void shouldReturn404WhenFollowerRelationMissing() throws Exception {
            authenticate();
            doThrow(new NotFoundException("This user is not following you", "TM_258"))
                    .when(followService).removeFollower(any(), any());
            mockMvc.perform(delete(BASE + "/followers/" + FOLLOWER_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_258"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /follows/{userUuid}/followers
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /follows/{userUuid}/followers")
    class GetFollowers {

        @Test
        void shouldReturn200WithFollowerPage() throws Exception {
            authenticate();
            Page<AuthUserResponse> page = new PageImpl<>(
                    List.of(user("alice")), PageRequest.of(0, 20), 1);
            when(followService.getFollowers(any(), any())).thenReturn(page);

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/followers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.content[0].username").value("alice"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            verify(followService).getFollowers(eq(TARGET_ID), any(Pageable.class));
        }

        @Test
        void shouldReturn200WithEmptyFollowerPage() throws Exception {
            authenticate();
            when(followService.getFollowers(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/followers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        @Test
        void shouldResolveMeToOwnUuid() throws Exception {
            authenticate();
            when(followService.getFollowers(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/me/followers")).andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(followService).getFollowers(uuid.capture(), any(Pageable.class));
            assertThat(uuid.getValue()).isEqualTo(ME_UUID.toString());
        }

        @Test
        void shouldApplyDefaultPageSizeAndCreatedAtDescSort() throws Exception {
            authenticate();
            when(followService.getFollowers(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/followers")).andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(followService).getFollowers(any(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void shouldHonorCustomPageAndSizeParams() throws Exception {
            authenticate();
            when(followService.getFollowers(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/followers")
                            .param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(followService).getFollowers(any(), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            when(followService.getFollowers(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_250"));
            mockMvc.perform(get(BASE + "/ghost/followers"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_250"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /follows/{userUuid}/following
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /follows/{userUuid}/following")
    class GetFollowing {

        @Test
        void shouldReturn200WithFollowingPage() throws Exception {
            authenticate();
            Page<AuthUserResponse> page = new PageImpl<>(
                    List.of(user("bob")), PageRequest.of(0, 20), 1);
            when(followService.getFollowing(any(), any())).thenReturn(page);

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/following"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.content[0].username").value("bob"));

            verify(followService).getFollowing(eq(TARGET_ID), any(Pageable.class));
        }

        @Test
        void shouldReturn200WithEmptyFollowingPage() throws Exception {
            authenticate();
            when(followService.getFollowing(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/" + TARGET_ID + "/following"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        @Test
        void shouldResolveMeToOwnUuid() throws Exception {
            authenticate();
            when(followService.getFollowing(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE + "/me/following")).andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(followService).getFollowing(uuid.capture(), any(Pageable.class));
            assertThat(uuid.getValue()).isEqualTo(ME_UUID.toString());
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            when(followService.getFollowing(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_250"));
            mockMvc.perform(get(BASE + "/ghost/following"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_250"));
        }
    }
}
