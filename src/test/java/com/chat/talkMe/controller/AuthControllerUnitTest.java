package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.CaptchaService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link AuthController}.
 *
 * <p>Uses a standalone {@link MockMvc} with a MOCKED {@link AuthService} / {@link CaptchaService}
 * and the real {@link GlobalExceptionHandler} wired in as controller advice. This lets us drive
 * every controller branch, every validation path, and every exception mapping deterministically,
 * and assert precise mock interactions (called / never-called / argument capture / no unexpected
 * interactions) — which the existing full-context {@code AuthControllerTest} integration test
 * cannot do.
 *
 * <p><b>Scope boundary:</b> Spring Security filter-chain concerns (missing/invalid/expired JWT,
 * role/permission enforcement, CSRF, CORS, locked/disabled accounts) are enforced by the security
 * filter chain and {@code SecurityConfig}, NOT by this controller, so they are intentionally out of
 * scope here and are exercised by the integration test instead. See the "uncovered scenarios" note
 * at the bottom of this file.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController (unit)")
class AuthControllerUnitTest {

    // ── Endpoints (controller is @RequestMapping("/auth"); the /api/v1 prefix is added by
    //    WebMvcConfig#configurePathMatch, which does NOT apply to standalone MockMvc) ──
    private static final String SIGNUP = "/auth/signup";
    private static final String LOGIN = "/auth/login";
    private static final String REFRESH = "/auth/refresh";
    private static final String LOGOUT = "/auth/logout";
    private static final String ME = "/auth/me";
    private static final String SESSIONS = "/auth/sessions";
    private static final String REVOKE_ALL = "/auth/sessions/revoke-all";
    private static final String FORGOT_PASSWORD = "/auth/forgot-password";
    private static final String RESET_PASSWORD = "/auth/reset-password";
    private static final String CHANGE_PASSWORD = "/auth/change-password";
    private static final String VERIFY_EMAIL = "/auth/verify-email";
    private static final String RESEND_VERIFICATION = "/auth/resend-verification";

    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final long GUEST_MAX_AGE = 7L * 24 * 60 * 60;   // 604800
    private static final long USER_MAX_AGE = 30L * 24 * 60 * 60;   // 2592000

    @Mock
    private AuthService authService;

    @Mock
    private CaptchaService captchaService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(authService, captchaService);
        // @Value fields are not resolved without a Spring context — set them explicitly.
        ReflectionTestUtils.setField(controller, "cookieSecure", false);
        ReflectionTestUtils.setField(controller, "cookieSameSite", "Lax");

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("testuser")
                .email("testuser@example.com")
                .name("Test User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(role))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Populate the SecurityContext so @AuthenticationPrincipal resolves to our test user. */
    private void authenticateAsTestUser() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void allowCaptcha() {
        when(captchaService.verify(any(), any())).thenReturn(true);
    }

    private static JwtTokensResponse tokens(String access, String refresh) {
        return JwtTokensResponse.builder()
                .accessToken(access)
                .expiresIn(900L)
                .refreshToken(refresh)
                .build();
    }

    private static AuthUserResponse authUser(String username) {
        return AuthUserResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .username(username)
                .name("Test User")
                .email("testuser@example.com")
                .age(25)
                .gender("male")
                .isGuest(false)
                .isVerified(true)
                .build();
    }

    private static LoginResponse loginResponse(String username, boolean guest, String refresh) {
        AuthUserResponse user = authUser(username);
        ReflectionTestUtils.setField(user, "isGuest", guest);
        return LoginResponse.builder()
                .user(user)
                .tokens(tokens("access-token-value", refresh))
                .build();
    }

    private static String signupPayload(String name, String username, String email,
                                         String password, Integer age, String gender) {
        return """
                {
                  "name": %s,
                  "username": %s,
                  "email": %s,
                  "password": %s,
                  "age": %s,
                  "gender": %s
                }
                """.formatted(q(name), q(username), q(email), q(password),
                age == null ? "null" : age.toString(), q(gender));
    }

    private static String validSignupPayload() {
        return signupPayload("New User", "newuser", "newuser@example.com", "password1", 25, "male");
    }

    /** JSON-quote a value, or emit literal null. */
    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/signup
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/signup")
    class Signup {

        @Test
        void shouldReturn200AndSetCookiesWhenSignupSucceeds() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "refresh-abc"));

            mockMvc.perform(post(SIGNUP)
                            .header(HttpHeaders.USER_AGENT, "JUnit-UA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSignupPayload()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("User Registered Successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_001"))
                    .andExpect(jsonPath("$.data.user.username").value("newuser"))
                    .andExpect(jsonPath("$.data.tokens.accessToken").value("access-token-value"))
                    // Refresh token must never leak into the JSON body (@JsonIgnore).
                    .andExpect(jsonPath("$.data.tokens.refreshToken").doesNotExist())
                    .andExpect(cookie().exists("refreshToken"))
                    .andExpect(cookie().value("refreshToken", "refresh-abc"))
                    .andExpect(cookie().httpOnly("refreshToken", true))
                    .andExpect(cookie().secure("refreshToken", false))
                    .andExpect(cookie().maxAge("refreshToken", (int) USER_MAX_AGE))
                    .andExpect(cookie().exists("csrf_token"))
                    .andExpect(cookie().httpOnly("csrf_token", false));
        }

        @Test
        void shouldPassDeserializedRequestAndUserAgentToService() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "refresh-abc"));

            mockMvc.perform(post(SIGNUP)
                            .header(HttpHeaders.USER_AGENT, "JUnit-UA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSignupPayload()))
                    .andExpect(status().isOk());

            ArgumentCaptor<SignupRequest> req = ArgumentCaptor.forClass(SignupRequest.class);
            ArgumentCaptor<String> ua = ArgumentCaptor.forClass(String.class);
            verify(authService, times(1)).signup(req.capture(), ua.capture(), any(HttpServletRequest.class));
            assertThat(req.getValue().getName()).isEqualTo("New User");
            assertThat(req.getValue().getUsername()).isEqualTo("newuser");
            assertThat(req.getValue().getEmail()).isEqualTo("newuser@example.com");
            assertThat(req.getValue().getAge()).isEqualTo(25);
            assertThat(req.getValue().getGender()).isEqualTo("male");
            assertThat(ua.getValue()).isEqualTo("JUnit-UA");
        }

        @Test
        void shouldPassNullUserAgentWhenHeaderAbsent() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "refresh-abc"));

            mockMvc.perform(post(SIGNUP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSignupPayload()))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> ua = ArgumentCaptor.forClass(String.class);
            verify(authService).signup(any(), ua.capture(), any());
            assertThat(ua.getValue()).isNull();
        }

        @Test
        void shouldReturn400AndSkipServiceWhenHoneypotFilled() throws Exception {
            String payload = """
                    {
                      "name": "Bot", "username": "botuser", "email": "bot@example.com",
                      "password": "password1", "age": 25, "gender": "male",
                      "website": "http://spam.example"
                    }
                    """;

            mockMvc.perform(post(SIGNUP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_403"));

            // Honeypot short-circuits BEFORE the CAPTCHA check and before any auth work.
            verify(captchaService, never()).verify(any(), any());
            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenCaptchaFails() throws Exception {
            when(captchaService.verify(any(), any())).thenReturn(false);

            mockMvc.perform(post(SIGNUP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSignupPayload()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_401"));

            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn409WhenServiceReportsDuplicate() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenThrow(new ConflictException("Email already registered", "TM_047"));

            mockMvc.perform(post(SIGNUP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSignupPayload()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_047"))
                    .andExpect(jsonPath("$.message").value("Email already registered"));
        }

        @Test
        void shouldReturn400WhenNameBlank() throws Exception {
            assertSignupValidationFails(signupPayload("", "newuser", "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenNameNull() throws Exception {
            assertSignupValidationFails(signupPayload(null, "newuser", "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            assertSignupValidationFails(
                    signupPayload(repeat('a', 101), "newuser", "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenEmailFormatInvalid() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "not-an-email", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenUsernameTooShort() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "ab", "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenUsernameTooLong() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", repeat('a', 31), "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenUsernameHasIllegalChars() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "bad user!", "a@b.com", "password1", 25, "male"));
        }

        @Test
        void shouldReturn400WhenPasswordTooShort() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "abc12", 25, "male"));
        }

        @Test
        void shouldReturn400WhenPasswordHasNoDigit() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "abcdefg", 25, "male"));
        }

        @Test
        void shouldReturn400WhenPasswordHasNoLetter() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "1234567", 25, "male"));
        }

        @Test
        void shouldReturn400WhenAgeBelowMinimum() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "password1", 17, "male"));
        }

        @Test
        void shouldReturn400WhenAgeAboveMaximum() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "password1", 100, "male"));
        }

        @Test
        void shouldReturn400WhenAgeIsZero() throws Exception {
            // Lower-bound rejection of @ValidAge (18..99), using an explicit in-band value so the
            // assertion does not depend on how the JSON mapper handles absent/null primitives.
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "password1", 0, "male"));
        }

        @Test
        void shouldReturn400WhenGenderNotAllowed() throws Exception {
            assertSignupValidationFails(signupPayload("Nm", "newuser", "a@b.com", "password1", 25, "other"));
        }

        @Test
        void shouldAcceptBoundaryAge18() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "r"));
            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                            .content(signupPayload("Nm", "newuser", "a@b.com", "password1", 18, "male")))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldAcceptBoundaryAge99AndCaseInsensitiveGender() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "r"));
            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                            .content(signupPayload("Nm", "newuser", "a@b.com", "password1", 99, "FEMALE")))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldAcceptUnicodeAndEmojiName() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any()))
                    .thenReturn(loginResponse("newuser", false, "r"));

            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                            .content(signupPayload("José 🌟 名字", "newuser", "a@b.com", "password1", 25, "male")))
                    .andExpect(status().isOk());

            ArgumentCaptor<SignupRequest> req = ArgumentCaptor.forClass(SignupRequest.class);
            verify(authService).signup(req.capture(), any(), any());
            assertThat(req.getValue().getName()).isEqualTo("José 🌟 名字");
        }

        @Test
        void shouldReturn500WhenBodyIsMalformedJson() throws Exception {
            // The controller advice has no dedicated HttpMessageNotReadableException handler, so a
            // malformed body currently falls through to the catch-all → 500. Pins current behaviour.
            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn500WhenUnexpectedServiceErrorOccurs() throws Exception {
            allowCaptcha();
            when(authService.signup(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(validSignupPayload()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        private void assertSignupValidationFails(String payload) throws Exception {
            mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            // Bean validation runs during argument binding — before verifyHuman and before the service.
            verifyNoInteractions(authService, captchaService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/login  (unified credentials / guest route, raw-body parsing)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/login")
    class Login {

        private static final String CREDENTIALS = """
                {"email":"testuser@example.com","password":"password123"}""";
        private static final String GUEST = """
                {"name":"Guest User","age":30,"gender":"male","isGuest":true}""";

        @Test
        void shouldReturn200WithUserCookieMaxAgeWhenCredentialsValid() throws Exception {
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenReturn(loginResponse("testuser", false, "refresh-user"));

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_002"))
                    .andExpect(jsonPath("$.data.user.username").value("testuser"))
                    .andExpect(cookie().value("refreshToken", "refresh-user"))
                    .andExpect(cookie().maxAge("refreshToken", (int) USER_MAX_AGE));

            verify(authService).login(any(), any(), any(), any());
            verify(authService, never()).loginAsGuest(any(), any(), any());
        }

        @Test
        void shouldReturn200WithGuestCookieMaxAgeWhenIsGuestTrue() throws Exception {
            allowCaptcha();
            when(authService.loginAsGuest(any(), any(), any()))
                    .thenReturn(loginResponse("guest_ab12", true, "refresh-guest"));

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(GUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.user.isGuest").value(true))
                    .andExpect(cookie().value("refreshToken", "refresh-guest"))
                    .andExpect(cookie().maxAge("refreshToken", (int) GUEST_MAX_AGE));

            verify(authService).loginAsGuest(any(), any(), any());
            verify(authService, never()).login(any(), any(), any(), any());
        }

        @Test
        void shouldForwardParsedCredentialsAndClientIpToService() throws Exception {
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenReturn(loginResponse("testuser", false, "r"));

            mockMvc.perform(post(LOGIN)
                            .header("X-Forwarded-For", "203.0.113.7, 70.41.3.18")
                            .header(HttpHeaders.USER_AGENT, "JUnit-UA")
                            .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isOk());

            ArgumentCaptor<LoginRequest> req = ArgumentCaptor.forClass(LoginRequest.class);
            ArgumentCaptor<String> ua = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(authService).login(req.capture(), ua.capture(), ip.capture(), any());
            assertThat(req.getValue().getEmail()).isEqualTo("testuser@example.com");
            assertThat(req.getValue().getPassword()).isEqualTo("password123");
            assertThat(ua.getValue()).isEqualTo("JUnit-UA");
            // X-Forwarded-For wins over remoteAddr; only the first hop is trusted.
            assertThat(ip.getValue()).isEqualTo("203.0.113.7");
        }

        @Test
        void shouldUseRemoteAddrWhenNoForwardedForHeader() throws Exception {
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenReturn(loginResponse("testuser", false, "r"));

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(authService).login(any(), any(), ip.capture(), any());
            assertThat(ip.getValue()).isEqualTo("127.0.0.1");
        }

        @Test
        void shouldReturn401WhenServiceRejectsCredentials() throws Exception {
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenThrow(new UnauthorizedException("Invalid credentials", "TM_024"));

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldReturn429WhenServiceRateLimitsLogin() throws Exception {
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenThrow(new com.chat.talkMe.exception.TooManyRequestsException(
                            "Too many failed attempts", "TM_025"));

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_025"));
        }

        @Test
        void shouldReturn400AndSkipServiceWhenHoneypotFilled() throws Exception {
            String body = """
                    {"email":"a@b.com","password":"password123","website":"spam"}""";
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_403"));
            verify(captchaService, never()).verify(any(), any());
            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenCaptchaFails() throws Exception {
            when(captchaService.verify(any(), any())).thenReturn(false);
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_401"));
            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn400WhenBodyIsNotJson() throws Exception {
            // parseBody() throws IllegalArgumentException → handled as TM_071 / 400.
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("garbage-not-json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
            verifyNoInteractions(authService);
        }

        @Test
        void shouldReturn400WhenBodyIsJsonArray() throws Exception {
            // A JSON array cannot deserialize into Map → IllegalArgumentException → TM_071 / 400.
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("[1,2,3]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
            verifyNoInteractions(authService);
        }

        @Test
        void shouldPassSqlInjectionStringThroughUnsanitized() throws Exception {
            // The controller does not sanitize input — this documents that the raw value reaches
            // the service layer (defense lives below, via parameterized queries / JPA).
            allowCaptcha();
            when(authService.login(any(), any(), any(), any()))
                    .thenThrow(new UnauthorizedException("Invalid credentials", "TM_024"));
            String body = """
                    {"email":"' OR '1'='1","password":"password123"}""";

            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());

            ArgumentCaptor<LoginRequest> req = ArgumentCaptor.forClass(LoginRequest.class);
            verify(authService).login(req.capture(), any(), any(), any());
            assertThat(req.getValue().getEmail()).isEqualTo("' OR '1'='1");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/refresh
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/refresh")
    class Refresh {

        @Test
        void shouldReturn200AndRotateCookiesWhenTokenValid() throws Exception {
            when(authService.refresh(eq("old-refresh"), any(), any()))
                    .thenReturn(tokens("new-access", "new-refresh"));

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "old-refresh")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_023"))
                    .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                    .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                    .andExpect(cookie().value("refreshToken", "new-refresh"))
                    .andExpect(cookie().maxAge("refreshToken", (int) USER_MAX_AGE))
                    .andExpect(cookie().exists("csrf_token"));
        }

        @Test
        void shouldForwardClientIpFromForwardedForHeader() throws Exception {
            when(authService.refresh(any(), any(), any())).thenReturn(tokens("a", "b"));

            mockMvc.perform(post(REFRESH)
                            .cookie(new Cookie("refreshToken", "old-refresh"))
                            .header("X-Forwarded-For", "198.51.100.9"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(authService).refresh(eq("old-refresh"), any(), ip.capture());
            assertThat(ip.getValue()).isEqualTo("198.51.100.9");
        }

        @Test
        void shouldReturn401AndSkipServiceWhenCookieMissing() throws Exception {
            mockMvc.perform(post(REFRESH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));
            verify(authService, never()).refresh(any(), any(), any());
        }

        @Test
        void shouldReturn401AndSkipServiceWhenCookieBlank() throws Exception {
            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_026"));
            verify(authService, never()).refresh(any(), any(), any());
        }

        @Test
        void shouldReturn401WhenServiceRejectsToken() throws Exception {
            when(authService.refresh(any(), any(), any()))
                    .thenThrow(new UnauthorizedException("Refresh token invalid or expired", "TM_027"));

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "stale")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_027"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/logout
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/logout")
    class Logout {

        @Test
        void shouldReturn200AndClearCookiesWhenTokenPresent() throws Exception {
            doNothing().when(authService).logout("tok-1");

            mockMvc.perform(post(LOGOUT).cookie(new Cookie("refreshToken", "tok-1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Logout Successful"))
                    .andExpect(jsonPath("$.messageCode").value("TM_003"))
                    .andExpect(cookie().maxAge("refreshToken", 0))
                    .andExpect(cookie().maxAge("csrf_token", 0));

            verify(authService, times(1)).logout("tok-1");
        }

        @Test
        void shouldReturn200AndClearCookiesWithoutCallingServiceWhenNoToken() throws Exception {
            mockMvc.perform(post(LOGOUT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(cookie().maxAge("refreshToken", 0))
                    .andExpect(cookie().maxAge("csrf_token", 0));

            verify(authService, never()).logout(any());
        }

        @Test
        void shouldNotCallServiceWhenTokenBlank() throws Exception {
            mockMvc.perform(post(LOGOUT).cookie(new Cookie("refreshToken", "")))
                    .andExpect(status().isOk());
            verify(authService, never()).logout(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /auth/me
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /auth/me")
    class GetMe {

        @Test
        void shouldReturn200WithCurrentUser() throws Exception {
            authenticateAsTestUser();
            when(authService.getCurrentUser(any())).thenReturn(authUser("testuser"));

            mockMvc.perform(get(ME))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.username").value("testuser"))
                    .andExpect(jsonPath("$.data.email").value("testuser@example.com"));

            verify(authService).getCurrentUser(testUser);
        }

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedly() throws Exception {
            authenticateAsTestUser();
            when(authService.getCurrentUser(any())).thenThrow(new RuntimeException("db down"));

            mockMvc.perform(get(ME))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /auth/me
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /auth/me")
    class UpdateProfile {

        @Test
        void shouldReturn200WhenProfileUpdated() throws Exception {
            authenticateAsTestUser();
            AuthUserResponse updated = authUser("testuser");
            ReflectionTestUtils.setField(updated, "name", "Updated Name");
            when(authService.updateProfile(any(), any())).thenReturn(updated);

            String body = """
                    {"name":"Updated Name","bio":"hello world"}""";
            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_060"))
                    .andExpect(jsonPath("$.data.name").value("Updated Name"));

            verify(authService).updateProfile(any(UpdateProfileRequest.class), eq(testUser));
        }

        @Test
        void shouldAcceptEmptyOptionalBody() throws Exception {
            authenticateAsTestUser();
            when(authService.updateProfile(any(), any())).thenReturn(authUser("testuser"));

            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldReturn400WhenNameExceedsMaxLength() throws Exception {
            authenticateAsTestUser();
            String body = """
                    {"name":"%s"}""".formatted(repeat('x', 101));
            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verify(authService, never()).updateProfile(any(), any());
        }

        @Test
        void shouldReturn400WhenBioExceedsMaxLength() throws Exception {
            authenticateAsTestUser();
            String body = """
                    {"bio":"%s"}""".formatted(repeat('b', 501));
            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn400WhenAgeOutOfRange() throws Exception {
            authenticateAsTestUser();
            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content("{\"age\":5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldPassXssPayloadThroughUnsanitized() throws Exception {
            authenticateAsTestUser();
            when(authService.updateProfile(any(), any())).thenReturn(authUser("testuser"));
            String xss = "<script>alert(1)</script>";
            String body = """
                    {"name":"%s"}""".formatted(xss);

            mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateProfileRequest> req = ArgumentCaptor.forClass(UpdateProfileRequest.class);
            verify(authService).updateProfile(req.capture(), any());
            assertThat(req.getValue().getName()).isEqualTo(xss);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /auth/sessions
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /auth/sessions")
    class GetSessions {

        @Test
        void shouldReturn200WithSessionList() throws Exception {
            authenticateAsTestUser();
            SessionResponse s = SessionResponse.builder()
                    .id("s-1").ipAddress("127.0.0.1").userAgent("Mozilla/5.0").isCurrent(true).build();
            when(authService.getSessions(any())).thenReturn(List.of(s));

            mockMvc.perform(get(SESSIONS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value("s-1"))
                    .andExpect(jsonPath("$.data[0].ipAddress").value("127.0.0.1"))
                    .andExpect(jsonPath("$.data[0].current").value(true));

            verify(authService).getSessions(testUser);
        }

        @Test
        void shouldReturn200WithEmptyListWhenNoSessions() throws Exception {
            authenticateAsTestUser();
            when(authService.getSessions(any())).thenReturn(List.of());

            mockMvc.perform(get(SESSIONS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /auth/sessions/{id}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /auth/sessions/{id}")
    class RevokeSession {

        @Test
        void shouldReturn200WhenSessionRevoked() throws Exception {
            authenticateAsTestUser();
            doNothing().when(authService).revokeSession(any(), any());

            mockMvc.perform(delete(SESSIONS + "/session-uuid-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_051"));

            verify(authService).revokeSession(eq("session-uuid-123"), eq(testUser));
        }

        @Test
        void shouldReturn404WhenSessionNotFound() throws Exception {
            authenticateAsTestUser();
            doThrow(new NotFoundException("Session not found", "TM_050"))
                    .when(authService).revokeSession(any(), any());

            mockMvc.perform(delete(SESSIONS + "/missing"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_050"));
        }

        @Test
        void shouldReturn403WhenRevokingAnotherUsersSession() throws Exception {
            authenticateAsTestUser();
            doThrow(new ForbiddenException("Not your session", "TM_053"))
                    .when(authService).revokeSession(any(), any());

            mockMvc.perform(delete(SESSIONS + "/foreign"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_053"));
        }

        @Test
        void shouldReturn403WhenSpringAccessDeniedRaised() throws Exception {
            authenticateAsTestUser();
            doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                    .when(authService).revokeSession(any(), any());

            mockMvc.perform(delete(SESSIONS + "/x"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn400WhenSessionIdIsInvalidUuid() throws Exception {
            authenticateAsTestUser();
            doThrow(new IllegalArgumentException("Invalid UUID string: xyz"))
                    .when(authService).revokeSession(any(), any());

            mockMvc.perform(delete(SESSIONS + "/xyz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/sessions/revoke-all
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/sessions/revoke-all")
    class RevokeAllSessions {

        @Test
        void shouldReturn200WhenAllOtherSessionsRevoked() throws Exception {
            authenticateAsTestUser();
            doNothing().when(authService).revokeAllSessions(any());

            mockMvc.perform(post(REVOKE_ALL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_052"));

            verify(authService).revokeAllSessions(testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/forgot-password
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/forgot-password")
    class ForgotPassword {

        @Test
        void shouldReturn200WhenEmailValid() throws Exception {
            doNothing().when(authService).forgotPassword(any());

            mockMvc.perform(post(FORGOT_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"testuser@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_036"));

            verify(authService).forgotPassword(any());
        }

        @Test
        void shouldReturn400WhenEmailFormatInvalid() throws Exception {
            mockMvc.perform(post(FORGOT_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verify(authService, never()).forgotPassword(any());
        }

        @Test
        void shouldReturn400WhenEmailBlank() throws Exception {
            mockMvc.perform(post(FORGOT_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/reset-password
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/reset-password")
    class ResetPassword {

        @Test
        void shouldReturn200WhenTokenAndPasswordValid() throws Exception {
            doNothing().when(authService).resetPassword(any());

            mockMvc.perform(post(RESET_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"valid-token\",\"password\":\"newpass1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Password reset successful"))
                    .andExpect(jsonPath("$.messageCode").value("TM_037"));

            verify(authService).resetPassword(any());
        }

        @Test
        void shouldReturn400WhenTokenBlank() throws Exception {
            mockMvc.perform(post(RESET_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"\",\"password\":\"newpass1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verify(authService, never()).resetPassword(any());
        }

        @Test
        void shouldReturn400WhenPasswordTooWeak() throws Exception {
            mockMvc.perform(post(RESET_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"valid-token\",\"password\":\"weak\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn400WhenServiceRejectsToken() throws Exception {
            doThrow(new com.chat.talkMe.exception.BadRequestException("Token expired", "TM_038"))
                    .when(authService).resetPassword(any());

            mockMvc.perform(post(RESET_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"expired\",\"password\":\"newpass1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_038"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/change-password
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/change-password")
    class ChangePassword {

        @Test
        void shouldReturn200WhenPasswordChanged() throws Exception {
            authenticateAsTestUser();
            doNothing().when(authService).changePassword(any(), any());

            mockMvc.perform(post(CHANGE_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpass1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_043"));

            verify(authService).changePassword(any(), eq(testUser));
        }

        @Test
        void shouldReturn401WhenCurrentPasswordWrong() throws Exception {
            authenticateAsTestUser();
            doThrow(new UnauthorizedException("Current password incorrect", "TM_042"))
                    .when(authService).changePassword(any(), any());

            mockMvc.perform(post(CHANGE_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"wrongpass1\",\"newPassword\":\"newpass1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.messageCode").value("TM_042"));
        }

        @Test
        void shouldReturn400WhenCurrentPasswordBlank() throws Exception {
            authenticateAsTestUser();
            mockMvc.perform(post(CHANGE_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"\",\"newPassword\":\"newpass1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verify(authService, never()).changePassword(any(), any());
        }

        @Test
        void shouldReturn400WhenNewPasswordTooWeak() throws Exception {
            authenticateAsTestUser();
            mockMvc.perform(post(CHANGE_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"password123\",\"newPassword\":\"weak\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/verify-email
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/verify-email")
    class VerifyEmail {

        @Test
        void shouldReturn200WhenTokenValid() throws Exception {
            doNothing().when(authService).verifyEmail("verify-token");

            mockMvc.perform(post(VERIFY_EMAIL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"verify-token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_405"));

            verify(authService).verifyEmail("verify-token");
        }

        @Test
        void shouldReturn400WhenTokenBlank() throws Exception {
            mockMvc.perform(post(VERIFY_EMAIL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verify(authService, never()).verifyEmail(any());
        }

        @Test
        void shouldReturn400WhenServiceRejectsToken() throws Exception {
            doThrow(new com.chat.talkMe.exception.BadRequestException("Invalid or expired token", "TM_407"))
                    .when(authService).verifyEmail(any());

            mockMvc.perform(post(VERIFY_EMAIL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"expired\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_407"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /auth/resend-verification
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/resend-verification")
    class ResendVerification {

        @Test
        void shouldReturn200WhenVerificationEmailSent() throws Exception {
            authenticateAsTestUser();
            doNothing().when(authService).resendVerificationEmail(any());

            mockMvc.perform(post(RESEND_VERIFICATION))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_406"));

            verify(authService).resendVerificationEmail(testUser);
        }

        @Test
        void shouldReturn409WhenAlreadyVerified() throws Exception {
            authenticateAsTestUser();
            doThrow(new ConflictException("Email already verified", "TM_408"))
                    .when(authService).resendVerificationEmail(any());

            mockMvc.perform(post(RESEND_VERIFICATION))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_408"));
        }
    }
}
