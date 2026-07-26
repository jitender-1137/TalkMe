package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.PostResponse;
import com.chat.talkMe.dto.response.SmartProfileCardResponse;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.FriendService;
import com.chat.talkMe.service.PostService;
import com.chat.talkMe.service.UserService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link UserController} — the hermetic complement to the
 * (infra-dependent) {@code UserControllerTest} integration test.
 *
 * <p>Standalone {@link MockMvc} with mocked {@link UserService}/{@link FriendService}/
 * {@link PostService}/{@link AuthService} + real {@link GlobalExceptionHandler}. Registers the
 * {@link PageableHandlerMethodArgumentResolver} (the {@code /{id}/posts} feed paginates).
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize} and per-method {@code @featureGuard}
 * gates are method-security (inactive in standalone MockMvc) — covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserController (unit)")
class UserControllerUnitTest {

    private static final String BASE = "/users";
    private static final String OTHER = "user-uuid-2";
    private static final String OK = "TM_000";
    private static final String VE = "VE_101";
    private static final String ERR500 = "TM_002";

    @Mock private UserService userService;
    @Mock private FriendService friendService;
    @Mock private PostService postService;
    @Mock private AuthService authService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(userService, friendService, postService, authService);

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

        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static UserResponse user(String username) {
        return UserResponse.builder().id("u-1").username(username).name("Test User").build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ── GET /users/me ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/me")
    class GetMe {
        @Test
        void shouldReturnCurrentUser() throws Exception {
            when(userService.getCurrentUser(testUser)).thenReturn(user("testuser"));
            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(OK))
                    .andExpect(jsonPath("$.data.username").value("testuser"));
            verify(userService).getCurrentUser(testUser);
        }

        @Test
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(userService.getCurrentUser(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
        }
    }

    // ── PATCH/PUT /users/me ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH/PUT /users/me (update profile)")
    class UpdateProfile {
        @Test
        void shouldUpdateViaPut() throws Exception {
            when(userService.updateProfile(any(), any())).thenReturn(user("testuser"));
            mockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"New Name\",\"bio\":\"hi\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_060"));

            ArgumentCaptor<UpdateProfileRequest> req = ArgumentCaptor.forClass(UpdateProfileRequest.class);
            verify(userService).updateProfile(req.capture(), eq(testUser));
            assertThat(req.getValue().getName()).isEqualTo("New Name");
        }

        @Test
        void shouldUpdateViaPatch() throws Exception {
            when(userService.updateProfile(any(), any())).thenReturn(user("testuser"));
            mockMvc.perform(patch(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Patched\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_060"));
            verify(userService).updateProfile(any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            mockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + repeat('x', 101) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VE));
            verifyNoInteractions(userService);
        }

        @Test
        void shouldReturn400WhenAgeOutOfRange() throws Exception {
            mockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"age\":5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VE));
        }

        @Test
        void shouldPassMildInjectionThroughUnsanitized() throws Exception {
            when(userService.updateProfile(any(), any())).thenReturn(user("testuser"));
            String bio = "hi ' OR 1=1 <b>x</b>";
            mockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bio\":\"" + bio + "\"}"))
                    .andExpect(status().isOk());
            ArgumentCaptor<UpdateProfileRequest> req = ArgumentCaptor.forClass(UpdateProfileRequest.class);
            verify(userService).updateProfile(req.capture(), any());
            assertThat(req.getValue().getBio()).isEqualTo(bio);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            mockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
            verifyNoInteractions(userService);
        }
    }

    // ── PUT /users/me/mood ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /users/me/mood")
    class UpdateMood {
        @Test
        void shouldUpdateMoodForwardingValue() throws Exception {
            when(userService.updateMood(eq("FLIRT"), any())).thenReturn(user("testuser"));
            mockMvc.perform(put(BASE + "/me/mood").param("value", "FLIRT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_060"));
            verify(userService).updateMood("FLIRT", testUser);
        }

        @Test
        void shouldReturn500WhenValueParamMissing() throws Exception {
            mockMvc.perform(put(BASE + "/me/mood"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
            verifyNoInteractions(userService);
        }

        @Test
        void shouldReturn400WhenServiceRejectsMood() throws Exception {
            when(userService.updateMood(any(), any()))
                    .thenThrow(new BadRequestException("Invalid mood", "TM_061"));
            mockMvc.perform(put(BASE + "/me/mood").param("value", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_061"));
        }
    }

    // ── avatar upload / remove ──────────────────────────────────────────────────

    @Nested
    @DisplayName("avatar")
    class Avatar {
        @Test
        void shouldUploadAvatar() throws Exception {
            when(userService.uploadAvatar(any(), any())).thenReturn(Map.of("url", "/talkMe/a.png"));
            MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "bytes".getBytes());

            mockMvc.perform(multipart(BASE + "/me/avatar").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_USER_001"))
                    .andExpect(jsonPath("$.data.url").value("/talkMe/a.png"));

            verify(userService).uploadAvatar(any(), eq(testUser));
        }

        @Test
        void shouldReturn500WhenFilePartMissing() throws Exception {
            // Required multipart part absent → MissingServletRequestPartException → catch-all 500.
            mockMvc.perform(multipart(BASE + "/me/avatar"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
            verifyNoInteractions(userService);
        }

        @Test
        void shouldRemoveAvatar() throws Exception {
            doNothing().when(userService).removeAvatar(any());
            mockMvc.perform(delete(BASE + "/me/avatar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_USER_002"));
            verify(userService).removeAvatar(testUser);
        }
    }

    // ── DELETE /users/me (account deletion) ─────────────────────────────────────

    @Nested
    @DisplayName("DELETE /users/me")
    class DeleteAccount {
        @Test
        void shouldForwardPasswordWhenBodyPresent() throws Exception {
            doNothing().when(authService).requestAccountDeletion(any(), any());
            mockMvc.perform(delete(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"secret1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_USER_003"));
            verify(authService).requestAccountDeletion(testUser, "secret1");
        }

        @Test
        void shouldForwardNullPasswordWhenBodyAbsent() throws Exception {
            doNothing().when(authService).requestAccountDeletion(any(), isNull());
            mockMvc.perform(delete(BASE + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_USER_003"));
            verify(authService).requestAccountDeletion(eq(testUser), isNull());
        }

        @Test
        void shouldReturn401WhenPasswordWrong() throws Exception {
            doThrow(new com.chat.talkMe.exception.UnauthorizedException("Wrong password", "TM_042"))
                    .when(authService).requestAccountDeletion(any(), any());
            mockMvc.perform(delete(BASE + "/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"nope\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_042"));
        }
    }

    // ── GET /users/{id}, /profile, /card ────────────────────────────────────────

    @Nested
    @DisplayName("GET user by id / profile / card")
    class GetById {
        @Test
        void shouldReturnUserById() throws Exception {
            when(userService.getUserById(eq(OTHER), any())).thenReturn(user("bob"));
            mockMvc.perform(get(BASE + "/" + OTHER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value("bob"));
            verify(userService).getUserById(OTHER, testUser);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(userService.getUserById(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));
            mockMvc.perform(get(BASE + "/" + OTHER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn400WhenIdIsInvalidUuid() throws Exception {
            when(userService.getUserById(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: xyz"));
            mockMvc.perform(get(BASE + "/xyz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        void shouldReturnProfile() throws Exception {
            when(userService.getUserById(eq(OTHER), any())).thenReturn(user("bob"));
            mockMvc.perform(get(BASE + "/" + OTHER + "/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value("bob"));
        }

        @Test
        void shouldReturnSmartProfileCard() throws Exception {
            when(userService.getSmartProfileCard(eq(OTHER), any()))
                    .thenReturn(SmartProfileCardResponse.builder().build());
            mockMvc.perform(get(BASE + "/" + OTHER + "/card"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
            verify(userService).getSmartProfileCard(OTHER, testUser);
        }
    }

    // ── GET /users/search ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/search")
    class Search {
        @Test
        void shouldSearchWithDefaultLimit() throws Exception {
            when(userService.searchUsers(eq("bo"), anyInt(), any(), any()))
                    .thenReturn(PaginatedResponse.<UserResponse>builder().items(List.of(user("bob"))).build());

            mockMvc.perform(get(BASE + "/search").param("q", "bo"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].username").value("bob"));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(userService).searchUsers(eq("bo"), limit.capture(), isNull(), eq(testUser));
            assertThat(limit.getValue()).isEqualTo(20);
        }

        @Test
        void shouldForwardCustomLimitAndCursor() throws Exception {
            when(userService.searchUsers(any(), anyInt(), any(), any()))
                    .thenReturn(PaginatedResponse.<UserResponse>builder().items(List.of()).build());
            mockMvc.perform(get(BASE + "/search").param("q", "x").param("limit", "5").param("cursor", "c1"))
                    .andExpect(status().isOk());
            verify(userService).searchUsers("x", 5, "c1", testUser);
        }

        @Test
        void shouldReturn500WhenQueryParamMissing() throws Exception {
            mockMvc.perform(get(BASE + "/search"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
            verifyNoInteractions(userService);
        }

        @Test
        void shouldReturn400WhenServiceRejectsShortQuery() throws Exception {
            when(userService.searchUsers(any(), anyInt(), any(), any()))
                    .thenThrow(new BadRequestException("Query too short", "TM_065"));
            mockMvc.perform(get(BASE + "/search").param("q", "a"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_065"));
        }
    }

    // ── block / unblock / blocked / report ──────────────────────────────────────

    @Nested
    @DisplayName("block / unblock / blocked / report")
    class BlockReport {
        @Test
        void shouldBlockUser() throws Exception {
            doNothing().when(friendService).blockUser(any(), any());
            mockMvc.perform(post(BASE + "/" + OTHER + "/block"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_067"));
            verify(friendService).blockUser(OTHER, testUser);
            verify(friendService, never()).unblockUser(any(), any());
        }

        @Test
        void shouldReturn400WhenBlockingSelf() throws Exception {
            doThrow(new BadRequestException("Cannot block yourself", "TM_070"))
                    .when(friendService).blockUser(any(), any());
            mockMvc.perform(post(BASE + "/self/block"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_070"));
        }

        @Test
        void shouldUnblockUser() throws Exception {
            doNothing().when(friendService).unblockUser(any(), any());
            mockMvc.perform(delete(BASE + "/" + OTHER + "/block"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_068"));
            verify(friendService).unblockUser(OTHER, testUser);
        }

        @Test
        void shouldReturnBlockedUsers() throws Exception {
            when(userService.getBlockedUsers(any())).thenReturn(
                    PaginatedResponse.<BlockedUserResponse>builder()
                            .items(List.of(BlockedUserResponse.builder().id(OTHER).name("Bob").build())).build());
            mockMvc.perform(get(BASE + "/blocked"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value(OTHER));
            verify(userService).getBlockedUsers(testUser);
        }

        @Test
        void shouldReportUserWithReasonAndDescription() throws Exception {
            doNothing().when(userService).reportUser(any(), any(), any(), any());
            mockMvc.perform(post(BASE + "/" + OTHER + "/report").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"spam\",\"description\":\"ads\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_REPORT_001"));
            verify(userService).reportUser(OTHER, "spam", "ads", testUser);
        }

        @Test
        void shouldReportUserWithDefaultReasonWhenOmitted() throws Exception {
            doNothing().when(userService).reportUser(any(), any(), any(), any());
            mockMvc.perform(post(BASE + "/" + OTHER + "/report").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
            verify(userService).reportUser(eq(OTHER), eq("other"), isNull(), eq(testUser));
        }

        @Test
        void shouldReturn500WhenReportBodyMissing() throws Exception {
            // reportUser's @RequestBody Map is required → missing body → message-not-readable → 500.
            mockMvc.perform(post(BASE + "/" + OTHER + "/report"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(ERR500));
            verifyNoInteractions(userService);
        }
    }

    // ── posts / mutual-friends / lobby ──────────────────────────────────────────

    @Nested
    @DisplayName("posts / mutual-friends / lobby")
    class Misc {
        @Test
        void shouldReturnUserPostsWithDefaultPaging() throws Exception {
            Page<PostResponse> page = new PageImpl<>(
                    List.of(PostResponse.builder().id("p-1").build()), PageRequest.of(0, 20), 1);
            when(postService.getProfileFeed(eq(OTHER), any(), any())).thenReturn(page);

            mockMvc.perform(get(BASE + "/" + OTHER + "/posts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value("p-1"));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(postService).getProfileFeed(eq(OTHER), pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void shouldReturnMutualFriends() throws Exception {
            when(userService.getMutualFriends(eq(OTHER), any()))
                    .thenReturn(MutualFriendsResponse.builder().count(2).users(List.of()).build());
            mockMvc.perform(get(BASE + "/" + OTHER + "/mutual-friends"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(2));
            verify(userService).getMutualFriends(OTHER, testUser);
        }

        @Test
        void shouldReturnLobbyUsers() throws Exception {
            when(userService.getLobbyUsers(any())).thenReturn(List.of(user("bob")));
            mockMvc.perform(get(BASE + "/lobby"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].username").value("bob"));
            verify(userService).getLobbyUsers(testUser);
        }

        @Test
        void shouldReturnEmptyLobby() throws Exception {
            when(userService.getLobbyUsers(any())).thenReturn(List.of());
            mockMvc.perform(get(BASE + "/lobby"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }
}
