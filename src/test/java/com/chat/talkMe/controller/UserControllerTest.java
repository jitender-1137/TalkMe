package com.chat.talkMe.controller;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.Post;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.FriendRequestRepository;
import com.chat.talkMe.repository.MatchReportRepository;
import com.chat.talkMe.repository.PostRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.UserPresenceRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StorageService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private com.chat.talkMe.moderation.ContentModerationService moderationService;

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
        com.chat.talkMe.moderation.ModerationResult clean =
                Mockito.mock(com.chat.talkMe.moderation.ModerationResult.class);
        Mockito.lenient().when(clean.isExplicit()).thenReturn(false);
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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "some-image-bytes".getBytes()
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
