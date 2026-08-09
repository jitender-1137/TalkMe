package com.chat.talkMe.controller;

import com.chat.talkMe.domain.RefreshToken;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.Session;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.RefreshTokenRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.SessionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PwnedPasswordService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context integration test for {@link com.chat.talkMe.controller.AuthController}.
 *
 * <p>{@code @SpringBootTest} boots the whole application context under the {@code test} profile and
 * drives real endpoints through a {@link MockMvc} built from the {@link WebApplicationContext} with
 * Spring Security applied ({@link SecurityMockMvcConfigurers#springSecurity()}). Unlike the
 * standalone {@code AuthControllerUnitTest}, this exercises the true filter chain, security config,
 * validation, controller advice, JPA repositories and Redis-backed reset-token flow end to end.
 *
 * <p>Real collaborators are wired via {@code @Autowired}; only {@link PwnedPasswordService} is
 * replaced with a {@code @MockitoBean} so the breached-password check never depends on the network
 * or a live breach list. Each test seeds a persisted {@code testUser} in {@link #setUp()} and wipes
 * sessions, refresh tokens and users in {@link #tearDown()}.
 */
@SpringBootTest
@ActiveProfiles("test")
public class AuthControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // The breached-password check (HaveIBeenPwned) rejects common passwords; mock it so the
    // signup/change/reset happy-paths don't depend on the network or a breach list.
    @MockitoBean
    private PwnedPasswordService pwnedPasswordService;

    private MockMvc mockMvc;
    private User testUser;

    /**
     * Builds a security-aware {@link MockMvc}, ensures {@code ROLE_USER} exists, persists a verified
     * {@code testUser}, and stubs the breached-password check to always pass.
     */
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
                .passwordHash(passwordEncoder.encode("password123"))
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        testUser = userRepository.save(testUser);

        Mockito.when(pwnedPasswordService.isBreached(ArgumentMatchers.anyString()))
                .thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Wraps the seeded {@code testUser} as the authenticated principal for {@code .with(user(...))}.
     */
    private CustomUserDetails testUserDetails() {
        return new CustomUserDetails(testUser);
    }

    @Test
    void testSignupSuccess() throws Exception {
        String signupPayload = """
                {
                  "name": "New User",
                  "username": "newuser",
                  "email": "newuser@example.com",
                  "password": "password123",
                  "age": 25,
                  "gender": "male"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User Registered Successfully"))
                .andExpect(jsonPath("$.data.user.username").value("newuser"))
                .andExpect(cookie().exists("refreshToken"))
                .andExpect(cookie().exists("csrf_token"));
    }

    /** Signup resolves the user's country from the Cloudflare {@code CF-IPCountry} header (IN → India). */
    @Test
    void testSignupSuccessWithCloudflareCountry() throws Exception {
        String signupPayload = """
                {
                  "name": "Cloudflare User",
                  "username": "cfuser",
                  "email": "cfuser@example.com",
                  "password": "password123",
                  "age": 28,
                  "gender": "female"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .header("CF-IPCountry", "IN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.country").value("India"));
    }

    @Test
    void testSignupDuplicateEmail() throws Exception {
        String signupPayload = """
                {
                  "name": "Another Name",
                  "username": "anotheruser",
                  "email": "testuser@example.com",
                  "password": "password123",
                  "age": 22,
                  "gender": "female"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_047"));
    }

    @Test
    void testSignupValidationFailure() throws Exception {
        String invalidPayload = """
                {
                  "name": "",
                  "username": "newuser",
                  "email": "invalid-email-format",
                  "password": "123",
                  "age": 15,
                  "gender": "other"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("VE_101"));
    }

    @Test
    void testLoginSuccess() throws Exception {
        String loginPayload = """
                {
                  "email": "testuser@example.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.username").value("testuser"))
                .andExpect(cookie().exists("refreshToken"))
                .andExpect(cookie().exists("csrf_token"));
    }

    /** A body with {@code isGuest:true} routes to guest login and returns a guest user. */
    @Test
    void testLoginGuestSuccess() throws Exception {
        String loginPayload = """
                {
                  "name": "Guest Guest",
                  "age": 30,
                  "gender": "male",
                  "isGuest": true
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.guest").value(true))
                .andExpect(cookie().exists("refreshToken"))
                .andExpect(cookie().exists("csrf_token"));
    }

    /** Guest login also resolves country from {@code CF-IPCountry} (US → United States). */
    @Test
    void testLoginGuestSuccessWithCloudflareCountry() throws Exception {
        String loginPayload = """
                {
                  "name": "Guest CF",
                  "age": 30,
                  "gender": "male",
                  "isGuest": true
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("CF-IPCountry", "US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.country").value("United States"));
    }

    @Test
    void testLoginBadCredentials() throws Exception {
        String loginPayload = """
                {
                  "email": "testuser@example.com",
                  "password": "wrongpassword1"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_024"));
    }

    @Test
    void testRefreshSuccess() throws Exception {
        String tokenStr = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(tokenStr)
                .user(testUser)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        Cookie refreshCookie = new Cookie("refreshToken", tokenStr);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.messageCode").value("TM_023"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(cookie().exists("refreshToken"));
    }

    @Test
    void testRefreshMissingCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_026"));
    }

    @Test
    void testLogoutSuccess() throws Exception {
        String tokenStr = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(tokenStr)
                .user(testUser)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        Cookie refreshCookie = new Cookie("refreshToken", tokenStr);
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(user(testUserDetails()))
                        .cookie(refreshCookie)
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logout Successful"))
                .andExpect(cookie().maxAge("refreshToken", 0))
                .andExpect(cookie().maxAge("csrf_token", 0));
    }

    @Test
    void testGetMeSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("testuser"));
    }

    @Test
    void testUpdateProfileSuccess() throws Exception {
        String updatePayload = """
                {
                  "name": "Updated Auth Name",
                  "bio": "New Bio"
                }
                """;

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(put("/api/v1/auth/me")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated Auth Name"));
    }

    @Test
    void testGetSessionsSuccess() throws Exception {
        sessionRepository.save(Session.builder()
                .user(testUser)
                .ipAddress("127.0.0.1")
                .userAgent("Mozilla/5.0")
                .isCurrent(true)
                .build());

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].ipAddress").value("127.0.0.1"));
    }

    @Test
    void testRevokeSessionSuccess() throws Exception {
        Session session = sessionRepository.save(Session.builder()
                .user(testUser)
                .ipAddress("127.0.0.1")
                .userAgent("Mozilla/5.0")
                .isCurrent(false)
                .build());

        String sessionUuid = session.getUuid().toString();
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(delete("/api/v1/auth/sessions/" + sessionUuid)
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Session terminated successfully"));
    }

    @Test
    void testRevokeAllSessionsSuccess() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(post("/api/v1/auth/sessions/revoke-all")
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .with(user(testUserDetails())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("All other sessions revoked successfully"));
    }

    @Test
    void testForgotPasswordSuccess() throws Exception {
        String payload = """
                {
                  "email": "testuser@example.com"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.messageCode").value("TM_036"));
    }

    @Test
    void testResetPasswordSuccess() throws Exception {
        // Seed a valid reset token the way forgotPassword does: Redis key
        // "pwreset:token:<sha256hex(token)>" → the user's uuid.
        String token = "valid-token-value";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String hash = HexFormat.of()
                .formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        redisTemplate.opsForValue().set("pwreset:token:" + hash, testUser.getUuid().toString());

        String payload = """
                {
                  "token": "valid-token-value",
                  "password": "newpassword123"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Password reset successful"));
    }

    @Test
    void testChangePasswordSuccess() throws Exception {
        String payload = """
                {
                  "currentPassword": "password123",
                  "newPassword": "newpassword123"
                }
                """;

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Password changed successfully"));
    }

    @Test
    void testChangePasswordWrongCurrent() throws Exception {
        String payload = """
                {
                  "currentPassword": "wrongpassword123",
                  "newPassword": "newpassword123"
                }
                """;

        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .with(user(testUserDetails()))
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageCode").value("TM_042"));
    }
}
