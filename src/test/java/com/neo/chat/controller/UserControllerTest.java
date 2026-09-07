package com.neo.chat.controller;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.Post;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.FriendRequestRepository;
import com.neo.chat.repository.MatchReportRepository;
import com.neo.chat.repository.PostRepository;
import com.neo.chat.repository.RoleRepository;
import com.neo.chat.repository.UserPresenceRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.StorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context integration test for {@link UserController}.
 *
 * <p>Boots the whole Spring context ({@code @SpringBootTest}, {@code test} profile) and drives the
 * real endpoints through a {@link MockMvc} built with {@code webAppContextSetup} + Spring Security
 * ({@link SecurityMockMvcConfigurers#springSecurity()}), so the class-level {@code @PreAuthorize}
 * gates, CSRF and JSON wiring are all live. Persistence uses the real (autowired) repositories:
 * each test seeds {@code testUser}/{@code targetUser}/{@code thirdUser} in {@code setUp} and wipes
 * every touched table in {@code tearDown}.
 *
 * <p>Only two collaborators are replaced with mocks ({@code @MockitoBean}): {@link StorageService}
 * (no real object store in tests) and {@link ContentModerationService} (its NSFW sidecar is
 * unavailable), the latter stubbed to return a non-explicit result so moderated endpoints proceed.
 *
 * <p>This is the infra-dependent complement to the hermetic {@code UserControllerUnitTest}: here
 * status codes and {@code messageCode}s are asserted against the genuine service + handler stack,
 * not stubbed exceptions.
 */
@SpringBootTest
@ActiveProfiles("test")
public class UserControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserPresenceRepository userPresenceRepository;

    @Autowired
    private FriendRepository friendRepository;

    @Autowired
    private FriendRequestRepository friendRequestRepository;

    @Autowired
    private BlockUserRepository blockUserRepository;

    @Autowired
    private MatchReportRepository matchReportRepository;

    @Autowired
    private PostRepository postRepository;

    @MockitoBean
    private StorageService storageService;

    // Avatar upload runs the (real) NSFW moderation sidecar, which is unavailable in tests —
    // mock it so uploadAvatar doesn't fail on a connection error.
    @MockitoBean
    private ContentModerationService moderationService;

    private MockMvc mockMvc;
    private User testUser;
    private User targetUser;
    private User thirdUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));

        testUser = User.builder()
                .username("testuser")
                .email("testuser@example.com")
                .name("Test User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        testUser = userRepository.save(testUser);

        targetUser = User.builder()
                .username("targetuser")
                .email("targetuser@example.com")
                .name("Target User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        targetUser = userRepository.save(targetUser);

        thirdUser = User.builder()
                .username("thirduser")
                .email("thirduser@example.com")
                .name("Third User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        thirdUser = userRepository.save(thirdUser);

        // ContentModerationService is mocked (its NSFW sidecar isn't available in tests). Several
        // endpoints moderate input (updateProfile → moderateText(bio), uploadAvatar → moderateUpload),
        // so return a non-explicit result by default to avoid NPEs on the unstubbed mock.
        ModerationResult clean =
                Mockito.mock(ModerationResult.class);
        Mockito.lenient().when(clean.explicit()).thenReturn(false);
        Mockito.lenient().when(moderationService.moderateText(any())).thenReturn(clean);
        Mockito.lenient().when(moderationService.moderateUpload(any())).thenReturn(clean);
    }

    @AfterEach
    void tearDown() {
        matchReportRepository.deleteAll();
        blockUserRepository.deleteAll();
        friendRepository.deleteAll();
        friendRequestRepository.deleteAll();
        postRepository.deleteAll();
        userPresenceRepository.deleteAll();
        userRepository.deleteAll();
    }

    private CustomUserDetails testUserDetails() {
        return new CustomUserDetails(testUser);
    }

    @Test
    void testGetMeSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.name").value("Test User"));
    }

    @Test
    void testGetMeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUpdateProfileSuccess() throws Exception {
        // NOTE: the service now rejects country changes (TM_099 "Country cannot be updated"),
        // so the update payload only carries editable fields.
        String updatePayload = """
                {
                  "name": "Updated Name",
                  "bio": "Developer bio"
                }
                """;

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated Name"))
                .andExpect(jsonPath("$.data.bio").value("Developer bio"));
    }

    @Test
    void testUpdateProfileInvalidName() throws Exception {
        String longName = "A".repeat(101);
        String updatePayload = """
                {
                  "name": "%s"
                }
                """.formatted(longName);

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("VE_101"));
    }

    @Test
    void testUploadAvatarSuccess() throws Exception {
        // Real JPEG magic bytes (0xFFD8FF …) so the avatar's magic-byte validation passes.
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                jpeg
        );

        // Moderation is stubbed clean in setUp. uploadAvatar calls the 3-arg
        // storeFile(file, "avatar", "profiles/<uuid>").
        Mockito.when(storageService.storeFile(any(), anyString(), anyString()))
                .thenReturn("http://example.com/avatar.jpg");

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(file)
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.avatarUrl").value("http://example.com/avatar.jpg"));
    }

    /**
     * No multipart {@code file} part → the required part is absent, which surfaces as a 500 rather
     * than a 4xx (there is no dedicated handler for the missing-part exception in this stack).
     */
    @Test
    void testUploadAvatarMissingFile() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void testRemoveAvatarSuccess() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(delete("/api/v1/users/me/avatar")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Avatar removed"));
    }

    @Test
    void testGetUserByIdSuccess() throws Exception {
        String targetUuid = targetUser.getUuid().toString();
        mockMvc.perform(get("/api/v1/users/" + targetUuid)
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("targetuser"));
    }

    @Test
    void testGetUserByIdNotFound() throws Exception {
        String randomUuid = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/users/" + randomUuid)
                        .with(user(testUserDetails())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_USER_NOT_FOUND"));
    }

    @Test
    void testGetUserByIdInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/users/invalid-uuid-string")
                        .with(user(testUserDetails())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
    }

    @Test
    void testGetUserProfileSuccess() throws Exception {
        String targetUuid = targetUser.getUuid().toString();
        mockMvc.perform(get("/api/v1/users/" + targetUuid + "/profile")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("targetuser"));
    }

    @Test
    void testSearchUsersSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", "target")
                        .param("limit", "10")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].username").value("targetuser"));
    }

    @Test
    void testSearchUsersQueryTooShort() throws Exception {
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", "t")
                        .with(user(testUserDetails())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_070"));
    }

    @Test
    void testBlockUserSuccess() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");
        String targetUuid = targetUser.getUuid().toString();

        mockMvc.perform(post("/api/v1/users/" + targetUuid + "/block")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User blocked"));
    }

    @Test
    void testBlockSelfBadRequest() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");
        String selfUuid = testUser.getUuid().toString();

        mockMvc.perform(post("/api/v1/users/" + selfUuid + "/block")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_071"));
    }

    /**
     * Pre-seeds an existing block of targetUser so the DELETE has something to remove.
     */
    @Test
    void testUnblockUserSuccess() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");
        String targetUuid = targetUser.getUuid().toString();

        blockUserRepository.save(BlockUser.builder().user(testUser).blocked(targetUser).build());

        mockMvc.perform(delete("/api/v1/users/" + targetUuid + "/block")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User unblocked"));
    }

    @Test
    void testGetBlockedUsersSuccess() throws Exception {
        blockUserRepository.save(BlockUser.builder().user(testUser).blocked(targetUser).build());

        mockMvc.perform(get("/api/v1/users/blocked")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].name").value("Target User"));
    }

    @Test
    void testReportUserSuccess() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");
        String targetUuid = targetUser.getUuid().toString();
        String payload = """
                {
                  "reason": "spam",
                  "description": "Spamming in chat"
                }
                """;

        mockMvc.perform(post("/api/v1/users/" + targetUuid + "/report")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Report submitted"));
    }

    @Test
    void testGetUserPostsSuccess() throws Exception {
        postRepository.save(Post.builder()
                .user(targetUser)
                .content("Hello World from target user")
                .build());

        String targetUuid = targetUser.getUuid().toString();
        mockMvc.perform(get("/api/v1/users/" + targetUuid + "/posts")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].content").value("Hello World from target user"));
    }

    /**
     * Seeds reciprocal friendships (testUser↔thirdUser and targetUser↔thirdUser) so that
     * {@code thirdUser} is the sole friend common to both testUser and targetUser, and asserts the
     * mutual-friends endpoint reports exactly that one overlap.
     */
    @Test
    void testGetMutualFriendsSuccess() throws Exception {
        friendRepository.save(Friend.builder().user(testUser).friend(thirdUser).build());
        friendRepository.save(Friend.builder().user(thirdUser).friend(testUser).build());

        friendRepository.save(Friend.builder().user(targetUser).friend(thirdUser).build());
        friendRepository.save(Friend.builder().user(thirdUser).friend(targetUser).build());

        String targetUuid = targetUser.getUuid().toString();
        mockMvc.perform(get("/api/v1/users/" + targetUuid + "/mutual-friends")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.users[0].username").value("thirduser"));
    }
}
