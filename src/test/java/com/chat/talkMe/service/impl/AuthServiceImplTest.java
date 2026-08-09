package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.FeatureAccessCache;
import com.chat.talkMe.domain.RefreshToken;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.Session;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.OAuthUserInfo;
import com.chat.talkMe.dto.request.ChangePasswordRequest;
import com.chat.talkMe.dto.request.ForgotPasswordRequest;
import com.chat.talkMe.dto.request.GuestLoginRequest;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.ResetPasswordRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.CountryDetectionResult;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.LookingForTag;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.PersonalityTrait;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.mapper.SessionMapper;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.moderation.ModerationResult;
import com.chat.talkMe.repository.PermissionRepository;
import com.chat.talkMe.repository.RefreshTokenRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.SessionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.CountryDetectionService;
import com.chat.talkMe.service.EmailService;
import com.chat.talkMe.service.FeatureAccessService;
import com.chat.talkMe.service.LoginAttemptService;
import com.chat.talkMe.service.PwnedPasswordService;
import com.chat.talkMe.service.ReputationRecorder;
import com.chat.talkMe.service.WebPushService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AuthServiceImpl} — the auth surface: password /
 * guest / OAuth login, signup, token refresh + logout, session list/revoke, password
 * reset + change, email verification, and account-deletion lifecycle.
 *
 * <p>Every public method is enumerated for its happy path(s), each branch/flag, and
 * every negative outcome with its exact {@code TM_###} code. Shared side effects of the
 * login pipeline (single-device token revoke, push-subscription sweep, session insert,
 * feature-cache evict) are asserted where they matter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl (unit)")
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private CountryDetectionService countryDetectionService;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private PwnedPasswordService pwnedPasswordService;
    @Mock
    private EmailService emailService;
    @Mock
    private WebPushService webPushService;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private FeatureAccessService featureAccessService;
    @Mock
    private FeatureAccessCache featureAccessCache;
    @Mock
    private ReputationRecorder reputationRecorder;

    private AuthServiceImpl service;

    private static final long ACCESS_TTL_MS = 900_000L;
    private static final long REFRESH_TTL_MS = 604_800_000L;
    private static final long GUEST_REFRESH_TTL_MS = 86_400_000L;
    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(
                userRepository, roleRepository, permissionRepository, refreshTokenRepository,
                sessionRepository, passwordEncoder, tokenProvider, userMapper, sessionMapper,
                countryDetectionService, loginAttemptService, redisTemplate, pwnedPasswordService,
                emailService, webPushService, moderationService, userSettingRepository,
                featureAccessService, featureAccessCache, reputationRecorder);

        ReflectionTestUtils.setField(service, "accessTokenExpirationMs", ACCESS_TTL_MS);
        ReflectionTestUtils.setField(service, "refreshTokenExpirationMs", REFRESH_TTL_MS);
        ReflectionTestUtils.setField(service, "guestRefreshTokenExpirationMs", GUEST_REFRESH_TTL_MS);
        ReflectionTestUtils.setField(service, "passwordResetTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "emailVerificationTtlMinutes", 1440L);
        ReflectionTestUtils.setField(service, "accountDeletionWindowDays", 30L);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000/");

        // Shared, lenient stubs: any Redis value-op path resolves through valueOps, and
        // userRepository.save echoes back the entity (assigning id/uuid on first insert)
        // so the post-save id/uuid reads inside the service don't NPE.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(999L);
            if (u.getUuid() == null) u.setUuid(UUID.randomUUID());
            return u;
        });
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private User activeUser() {
        User u = User.builder()
                .username("alice")
                .email("alice@example.com")
                .passwordHash("$2a$hash")
                .name("Alice")
                .isGuest(false)
                .isVerified(true)
                .build();
        u.setId(1L);
        u.setUuid(USER_UUID);
        return u;
    }

    private CountryDetectionResult detection(String country) {
        return CountryDetectionResult.builder()
                .country(country).source("GeoIP").clientIp("1.2.3.4")
                .city("Pune").region("Maharashtra").build();
    }

    /**
     * Detection carrying only a country (no city/region), for the country-backfill edge branches.
     */
    private CountryDetectionResult detectionOnly(String country) {
        return CountryDetectionResult.builder()
                .country(country).source("GeoIP").clientIp("1.2.3.4").build();
    }

    /**
     * Stub the tail of {@code generateLoginResponse} shared by every login-producing path.
     */
    private void stubLoginPipeline() {
        when(tokenProvider.generateToken(anyString(), anyBoolean())).thenReturn("access-jwt");
        when(userMapper.toAuthUserResponse(any(User.class))).thenReturn(new AuthUserResponse());
        when(featureAccessService.effectiveWireNames(any(User.class))).thenReturn(Set.of("chat"));
    }

    private HttpServletRequest httpRequest() {
        return mock(HttpServletRequest.class);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("login")
    class Login {

        private LoginRequest req(String id, String pw) {
            LoginRequest r = new LoginRequest();
            r.setEmail(id);
            r.setPassword(pw);
            return r;
        }

        @Test
        @DisplayName("valid credentials → tokens returned, success recorded, sign-in alert sent")
        void happyPath() {
            User user = activeUser();
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pw", "$2a$hash")).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            LoginResponse resp = service.login(req("alice", "pw"), "Mozilla", "1.2.3.4", httpRequest());

            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
            assertThat(resp.getTokens().getExpiresIn()).isEqualTo(ACCESS_TTL_MS / 1000);
            verify(loginAttemptService).recordSuccess("alice", "1.2.3.4");
            verify(refreshTokenRepository).revokeAllUserTokens(user);
            verify(webPushService).removeAllSubscriptionsForUser(1L);
            verify(sessionRepository).save(any(Session.class));
            verify(emailService).sendLoginAlertEmail(eq("alice@example.com"), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("identifier not matched by username falls back to email lookup")
        void fallsBackToEmailLookup() {
            User user = activeUser();
            when(userRepository.findByUsernameIgnoreCase("alice@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pw", "$2a$hash")).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            LoginResponse resp = service.login(req("alice@example.com", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(resp.getUser()).isNotNull();
        }

        @Test
        @DisplayName("blank country is backfilled from the detected country")
        void backfillsCountryWhenBlank() {
            User user = activeUser();
            user.setCountry(null);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isEqualTo("India");
        }

        @Test
        @DisplayName("existing country is never overwritten by detection")
        void doesNotOverwriteExistingCountry() {
            User user = activeUser();
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isEqualTo("Canada");
        }

        @Test
        @DisplayName("location detection failure is swallowed — login still succeeds")
        void detectionFailureSwallowed() {
            User user = activeUser();
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenThrow(new RuntimeException("geo down"));
            stubLoginPipeline();

            LoginResponse resp = service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
        }

        @Test
        @DisplayName("soft-deleted account within recovery window is restored on login")
        void restoresDeletedWithinWindow() {
            User user = activeUser();
            user.setDeleted(true);
            user.setDeletionRequestedAt(Instant.now().minus(Duration.ofDays(3)));
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.isDeleted()).isFalse();
            assertThat(user.getDeletionRequestedAt()).isNull();
        }

        @Test
        @DisplayName("account locked out → TooManyRequestsException propagates, no lookup")
        void lockedOut() {
            doThrow(new TooManyRequestsException("locked", "TM_106"))
                    .when(loginAttemptService).assertNotBlocked("alice", "1.2.3.4");

            assertThatThrownBy(() -> service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOf(TooManyRequestsException.class);
            verify(userRepository, never()).findByUsernameIgnoreCase(any());
        }

        @Test
        @DisplayName("unknown user → TM_024 + failure recorded")
        void unknownUser() {
            when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(req("ghost", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
            verify(loginAttemptService).recordFailure("ghost", "1.2.3.4");
        }

        @Test
        @DisplayName("guest account using password flow → TM_029, no failure recorded")
        void guestRejected() {
            User guest = activeUser();
            guest.setGuest(true);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_029"));
            verify(loginAttemptService, never()).recordFailure(any(), any());
        }

        @Test
        @DisplayName("banned account → TM_030 + failure recorded")
        void bannedRejected() {
            User user = activeUser();
            user.setBanned(true);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_030"));
            verify(loginAttemptService).recordFailure("alice", "1.2.3.4");
        }

        @Test
        @DisplayName("wrong password → TM_024 + failure recorded")
        void wrongPassword() {
            User user = activeUser();
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);

            assertThatThrownBy(() -> service.login(req("alice", "bad"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
            verify(loginAttemptService).recordFailure("alice", "1.2.3.4");
        }

        @Test
        @DisplayName("soft-deleted past the recovery window → TM_024, not restored")
        void deletedPastWindow() {
            User user = activeUser();
            user.setDeleted(true);
            user.setDeletionRequestedAt(Instant.now().minus(Duration.ofDays(31)));
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }

        @Test
        @DisplayName("deleted with null requestedAt → treated as past window → TM_024")
        void deletedNullRequestedAt() {
            User user = activeUser();
            user.setDeleted(true);
            user.setDeletionRequestedAt(null);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("signup")
    class Signup {

        private SignupRequest req() {
            SignupRequest r = new SignupRequest();
            r.setName("New User");
            r.setUsername("newuser");
            r.setEmail("New@Example.com");
            r.setPassword("Sup3rStr0ng!");
            r.setAge(25);
            r.setGender("male");
            return r;
        }

        @Test
        @DisplayName("valid signup → user persisted (lowercased email, unverified), verification email sent")
        void happyPath() {
            when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
            when(pwnedPasswordService.isBreached("Sup3rStr0ng!")).thenReturn(false);
            when(moderationService.moderateText("New User")).thenReturn(ModerationResult.clean());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(passwordEncoder.encode("Sup3rStr0ng!")).thenReturn("$enc");
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
            stubLoginPipeline();

            LoginResponse resp = service.signup(req(), "UA", httpRequest());

            // Persisted once on insert, again by the login pipeline (last-location write).
            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            User saved = cap.getValue();
            assertThat(saved.getEmail()).isEqualTo("new@example.com");
            assertThat(saved.isGuest()).isFalse();
            assertThat(saved.isVerified()).isFalse();
            assertThat(saved.getPasswordHash()).isEqualTo("$enc");
            assertThat(saved.getCountry()).isEqualTo("India");
            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
            verify(emailService).sendVerificationEmail(eq("new@example.com"), any(), anyString(), eq(1440L));
        }

        @Test
        @DisplayName("resolves a valid referrer and attributes it on the new user")
        void resolvesReferrer() {
            SignupRequest r = req();
            r.setReferredByUsername("@sponsor");
            User referrer = activeUser();
            referrer.setUsername("sponsor");
            when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
            when(pwnedPasswordService.isBreached(any())).thenReturn(false);
            when(moderationService.moderateText(any())).thenReturn(ModerationResult.clean());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(userRepository.findByUsernameIgnoreCase("sponsor")).thenReturn(Optional.of(referrer));
            when(valueOps.setIfAbsent(anyString(), any(), any())).thenReturn(true);
            stubLoginPipeline();

            service.signup(r, "UA", httpRequest());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            assertThat(cap.getValue().getReferredBy()).isSameAs(referrer);
        }

        @Test
        @DisplayName("email already registered → TM_047")
        void emailConflict() {
            when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(true);

            assertThatThrownBy(() -> service.signup(req(), "UA", httpRequest()))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_047"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("breached password → TM_496")
        void breachedPassword() {
            when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
            when(pwnedPasswordService.isBreached("Sup3rStr0ng!")).thenReturn(true);

            assertThatThrownBy(() -> service.signup(req(), "UA", httpRequest()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_496"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit display name → ContentModerationException (TM_490)")
        void explicitName() {
            when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
            when(pwnedPasswordService.isBreached(any())).thenReturn(false);
            when(moderationService.moderateText("New User"))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.PROFANITY, 0.9, List.of()));

            assertThatThrownBy(() -> service.signup(req(), "UA", httpRequest()))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(userRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("loginAsGuest")
    class LoginAsGuest {

        private GuestLoginRequest req() {
            GuestLoginRequest r = new GuestLoginRequest();
            r.setName("Guesty");
            r.setAge(22);
            r.setGender("female");
            return r;
        }

        @Test
        @DisplayName("creates an unverified guest with a generated username and returns tokens")
        void happyPath() {
            when(roleRepository.findByName("ROLE_GUEST"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_GUEST").build()));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            LoginResponse resp = service.loginAsGuest(req(), "UA", httpRequest());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            User guest = cap.getValue();
            assertThat(guest.isGuest()).isTrue();
            assertThat(guest.isVerified()).isFalse();
            assertThat(guest.getUsername()).startsWith("guest_");
            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
        }

        @Test
        @DisplayName("missing ROLE_GUEST is created on demand")
        void createsRoleWhenAbsent() {
            when(roleRepository.findByName("ROLE_GUEST")).thenReturn(Optional.empty());
            when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.loginAsGuest(req(), "UA", httpRequest());

            verify(roleRepository).save(any(Role.class));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("oauthLogin")
    class OauthLogin {

        private OAuthUserInfo info() {
            return OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("New@Gmail.com").emailVerified(true)
                    .name("Google Person").picture("http://pic").build();
        }

        @Test
        @DisplayName("first-time Google user → provisioned, welcome email sent, no login alert")
        void provisionsNewUser() {
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info(), "UA", httpRequest());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            User created = cap.getValue();
            assertThat(created.getGoogleId()).isEqualTo("g-sub-1");
            assertThat(created.getEmail()).isEqualTo("new@gmail.com");
            assertThat(created.isVerified()).isTrue();
            assertThat(created.getCountry()).isEqualTo("India");
            verify(emailService).sendWelcomeEmail(eq("new@gmail.com"), any(), anyString());
            verify(emailService, never()).sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("existing account matched by email → Google identity + fields backfilled, login alert sent")
        void backfillsExistingUser() {
            User existing = activeUser();
            existing.setGoogleId(null);
            existing.setProfileImage(null);
            existing.setAge(0);
            existing.setGender(null);
            existing.setVerified(false);
            existing.setCountry(null);
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("alice@example.com").emailVerified(true)
                    .name("Alice").picture("http://newpic").age(30).gender("female").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.getGoogleId()).isEqualTo("g-sub-1");
            assertThat(existing.getProfileImage()).isEqualTo("http://newpic");
            assertThat(existing.getAge()).isEqualTo(30);
            assertThat(existing.getGender()).isEqualTo("female");
            assertThat(existing.isVerified()).isTrue();
            assertThat(existing.getCountry()).isEqualTo("India");
            verify(emailService).sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
            verify(emailService, never()).sendWelcomeEmail(any(), any(), any());
        }

        @Test
        @DisplayName("Google sign-in recovers a soft-deleted account")
        void recoversDeletedAccount() {
            User existing = activeUser();
            existing.setGoogleId("g-sub-1");
            existing.setDeleted(true);
            existing.setDeletionRequestedAt(Instant.now().minus(Duration.ofDays(2)));
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info(), "UA", httpRequest());

            assertThat(existing.isDeleted()).isFalse();
            assertThat(existing.getDeletionRequestedAt()).isNull();
        }

        @Test
        @DisplayName("fully-populated existing user → no backfill save (only the login-pipeline save)")
        void noBackfillWhenClean() {
            User existing = activeUser();
            existing.setGoogleId("g-sub-1");
            existing.setProfileImage("http://have");
            existing.setAge(40);
            existing.setGender("male");
            existing.setVerified(true);
            existing.setCountry("Canada");
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info(), "UA", httpRequest());

            // generateLoginResponse always saves once; a second save would signal a dirty backfill.
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("create race (DataIntegrityViolation) → reuse the row that won the race")
        void reusesRowOnCreateRace() {
            User winner = activeUser();
            when(userRepository.findByGoogleId("g-sub-1"))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(userRepository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            // First save (the new row) loses the race; the later login-pipeline save succeeds.
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("dup"))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubLoginPipeline();

            service.oauthLogin(info(), "UA", httpRequest());

            // Winner row reused → not provisioned → login alert (not welcome).
            verify(emailService, never()).sendWelcomeEmail(any(), any(), any());
        }

        @Test
        @DisplayName("create race with no surviving row → the integrity violation is rethrown")
        void rethrowsWhenNoSurvivor() {
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email(null).emailVerified(true).name("X").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("dup"));

            assertThatThrownBy(() -> service.oauthLogin(info, "UA", httpRequest()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("refresh")
    class Refresh {

        private RefreshToken validToken(User user) {
            return RefreshToken.builder()
                    .user(user).token("old-token")
                    .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                    .revoked(false).build();
        }

        @Test
        @DisplayName("valid token → rotated, old revoked, new tokens returned")
        void happyPath() {
            User user = activeUser();
            RefreshToken token = validToken(user);
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken("alice", false)).thenReturn("new-access");
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of());

            var resp = service.refresh("old-token", "UA", "1.2.3.4");

            assertThat(token.isRevoked()).isTrue();
            assertThat(token.getReplacedByToken()).isNotBlank();
            assertThat(resp.getAccessToken()).isEqualTo("new-access");
            assertThat(resp.getExpiresIn()).isEqualTo(ACCESS_TTL_MS / 1000);
            verify(refreshTokenRepository).saveAndFlush(token);
            ArgumentCaptor<RefreshToken> cap = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(cap.capture());
            assertThat(cap.getValue().getToken()).isEqualTo(token.getReplacedByToken());
        }

        @Test
        @DisplayName("matching session (ip+userAgent) has its lastActiveAt bumped")
        void bumpsMatchingSession() {
            User user = activeUser();
            RefreshToken token = validToken(user);
            Session match = Session.builder().user(user).ipAddress("1.2.3.4").userAgent("UA").build();
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken(any(), anyBoolean())).thenReturn("new-access");
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of(match));

            service.refresh("old-token", "UA", "1.2.3.4");

            verify(sessionRepository).save(match);
        }

        @Test
        @DisplayName("non-matching session is not touched")
        void ignoresNonMatchingSession() {
            User user = activeUser();
            RefreshToken token = validToken(user);
            Session other = Session.builder().user(user).ipAddress("9.9.9.9").userAgent("Other").build();
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken(any(), anyBoolean())).thenReturn("new-access");
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of(other));

            service.refresh("old-token", "UA", "1.2.3.4");

            verify(sessionRepository, never()).save(other);
        }

        @Test
        @DisplayName("guest gets the guest refresh-token expiry")
        void guestExpiry() {
            User guest = activeUser();
            guest.setGuest(true);
            RefreshToken token = validToken(guest);
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken("alice", true)).thenReturn("new-access");
            when(sessionRepository.findByUserAndIsDeletedFalse(guest)).thenReturn(List.of());

            service.refresh("old-token", "UA", "1.2.3.4");

            ArgumentCaptor<RefreshToken> cap = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(cap.capture());
            Instant expected = Instant.now().plusMillis(GUEST_REFRESH_TTL_MS);
            assertThat(cap.getValue().getExpiresAt()).isBetween(
                    expected.minusSeconds(10), expected.plusSeconds(10));
        }

        @Test
        @DisplayName("unknown token → TM_026")
        void unknownToken() {
            when(refreshTokenRepository.findByToken("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("nope", "UA", "1.2.3.4"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_026"));
        }

        @Test
        @DisplayName("already-revoked token → TM_026")
        void revokedToken() {
            User user = activeUser();
            RefreshToken token = validToken(user);
            token.setRevoked(true);
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.refresh("old-token", "UA", "1.2.3.4"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_026"));
        }

        @Test
        @DisplayName("expired token → TM_026")
        void expiredToken() {
            User user = activeUser();
            RefreshToken token = RefreshToken.builder()
                    .user(user).token("old-token")
                    .expiresAt(Instant.now().minus(Duration.ofDays(1)))
                    .revoked(false).build();
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.refresh("old-token", "UA", "1.2.3.4"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_026"));
        }

        @Test
        @DisplayName("optimistic-lock on rotation flush → TM_026")
        void optimisticLock() {
            User user = activeUser();
            RefreshToken token = validToken(user);
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken(any(), anyBoolean())).thenReturn("new-access");
            when(refreshTokenRepository.saveAndFlush(token))
                    .thenThrow(new OptimisticLockingFailureException("raced"));

            assertThatThrownBy(() -> service.refresh("old-token", "UA", "1.2.3.4"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_026"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("known token → revoked and saved")
        void revokesKnownToken() {
            User user = activeUser();
            RefreshToken token = RefreshToken.builder().user(user).token("t").build();
            when(refreshTokenRepository.findByToken("t")).thenReturn(Optional.of(token));

            service.logout("t");

            assertThat(token.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(token);
        }

        @Test
        @DisplayName("unknown token → silent no-op")
        void unknownTokenNoop() {
            when(refreshTokenRepository.findByToken("t")).thenReturn(Optional.empty());

            service.logout("t");

            verify(refreshTokenRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getSessions")
    class GetSessions {

        @Test
        @DisplayName("maps the user's active sessions to responses")
        void mapsSessions() {
            User user = activeUser();
            Session s = Session.builder().user(user).build();
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of(s));
            when(sessionMapper.toSessionResponse(s))
                    .thenReturn(SessionResponse.builder().id("sess-1").build());

            List<SessionResponse> out = service.getSessions(user);

            assertThat(out).extracting(SessionResponse::getId).containsExactly("sess-1");
        }

        @Test
        @DisplayName("no sessions → empty list, no NPE")
        void emptyList() {
            User user = activeUser();
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of());

            assertThat(service.getSessions(user)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("revokeSession")
    class RevokeSession {

        @Test
        @DisplayName("owner revokes own session → soft-deleted")
        void revokesOwn() {
            User user = activeUser();
            UUID sid = UUID.randomUUID();
            Session s = Session.builder().user(user).build();
            when(sessionRepository.findByUuid(sid)).thenReturn(Optional.of(s));

            service.revokeSession(sid.toString(), user);

            assertThat(s.isDeleted()).isTrue();
            verify(sessionRepository).save(s);
        }

        @Test
        @DisplayName("session not found → TM_053")
        void notFound() {
            User user = activeUser();
            UUID sid = UUID.randomUUID();
            when(sessionRepository.findByUuid(sid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeSession(sid.toString(), user))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_053"));
        }

        @Test
        @DisplayName("revoking another user's session → TM_103")
        void notOwner() {
            User owner = activeUser();
            User attacker = activeUser();
            attacker.setId(2L);
            UUID sid = UUID.randomUUID();
            Session s = Session.builder().user(owner).build();
            when(sessionRepository.findByUuid(sid)).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> service.revokeSession(sid.toString(), attacker))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(sessionRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("revokeAllSessions")
    class RevokeAllSessions {

        @Test
        @DisplayName("all non-current sessions deleted; current session kept")
        void keepsCurrent() {
            User user = activeUser();
            Session current = Session.builder().user(user).isCurrent(true).build();
            Session other = Session.builder().user(user).isCurrent(false).build();
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of(current, other));

            service.revokeAllSessions(user);

            assertThat(current.isDeleted()).isFalse();
            assertThat(other.isDeleted()).isTrue();
            verify(sessionRepository).save(other);
            verify(sessionRepository, never()).save(current);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("forgotPassword")
    class ForgotPassword {

        private ForgotPasswordRequest req(String email) {
            ForgotPasswordRequest r = new ForgotPasswordRequest();
            r.setEmail(email);
            return r;
        }

        @Test
        @DisplayName("active account → reset token stored in Redis and email sent")
        void sendsResetLink() {
            User user = activeUser();
            when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

            service.forgotPassword(req("alice@example.com"));

            verify(valueOps).set(anyString(), eq(USER_UUID.toString()), any(Duration.class));
            verify(emailService).sendPasswordResetEmail(eq("alice@example.com"), any(), anyString(), eq(30L));
        }

        @Test
        @DisplayName("unknown email → silent no-op (anti-enumeration)")
        void unknownEmail() {
            when(userRepository.findByEmailIgnoreCase("ghost@x.com")).thenReturn(Optional.empty());

            service.forgotPassword(req("ghost@x.com"));

            verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("guest account → no reset email")
        void guestNoEmail() {
            User guest = activeUser();
            guest.setGuest(true);
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(guest));

            service.forgotPassword(req("alice@example.com"));

            verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("soft-deleted account → no reset email")
        void deletedNoEmail() {
            User user = activeUser();
            user.setDeleted(true);
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));

            service.forgotPassword(req("alice@example.com"));

            verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("cooldown active → suppressed, no token stored, no email")
        void cooldownSuppresses() {
            User user = activeUser();
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

            service.forgotPassword(req("alice@example.com"));

            verify(valueOps, never()).set(anyString(), eq(USER_UUID.toString()), any(Duration.class));
            verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), anyLong());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        private ResetPasswordRequest req(String token, String pw) {
            ResetPasswordRequest r = new ResetPasswordRequest();
            r.setToken(token);
            r.setPassword(pw);
            return r;
        }

        @Test
        @DisplayName("valid token → password reset, token consumed, all tokens revoked")
        void happyPath() {
            User user = activeUser();
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
            when(pwnedPasswordService.isBreached("NewP4ss!")).thenReturn(false);
            when(passwordEncoder.encode("NewP4ss!")).thenReturn("$new");

            service.resetPassword(req("raw-token", "NewP4ss!"));

            assertThat(user.getPasswordHash()).isEqualTo("$new");
            verify(userRepository).save(user);
            verify(redisTemplate).delete(anyString());
            verify(refreshTokenRepository).revokeAllUserTokens(user);
        }

        @Test
        @DisplayName("blank token → TM_038")
        void blankToken() {
            assertThatThrownBy(() -> service.resetPassword(req("  ", "NewP4ss!")))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_038"));
        }

        @Test
        @DisplayName("token not present in Redis → TM_038")
        void tokenMiss() {
            when(valueOps.get(anyString())).thenReturn(null);

            assertThatThrownBy(() -> service.resetPassword(req("raw-token", "NewP4ss!")))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_038"));
        }

        @Test
        @DisplayName("user for the token no longer exists → TM_038")
        void userMissing() {
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword(req("raw-token", "NewP4ss!")))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_038"));
        }

        @Test
        @DisplayName("stored value is not a valid UUID → TM_038")
        void badUuid() {
            when(valueOps.get(anyString())).thenReturn("not-a-uuid");

            assertThatThrownBy(() -> service.resetPassword(req("raw-token", "NewP4ss!")))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_038"));
        }

        @Test
        @DisplayName("breached new password → TM_496, nothing saved")
        void breachedPassword() {
            User user = activeUser();
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
            when(pwnedPasswordService.isBreached("NewP4ss!")).thenReturn(true);

            assertThatThrownBy(() -> service.resetPassword(req("raw-token", "NewP4ss!")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_496"));
            verify(userRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("verifyEmail")
    class VerifyEmail {

        @Test
        @DisplayName("valid token on unverified user → verified, cache evicted, welcome sent")
        void happyPath() {
            User user = activeUser();
            user.setVerified(false);
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));

            service.verifyEmail("raw-token");

            assertThat(user.isVerified()).isTrue();
            verify(userRepository).save(user);
            verify(featureAccessCache).evict(1L);
            verify(emailService).sendWelcomeEmail(eq("alice@example.com"), any(), anyString());
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("already verified → idempotent no-op (token consumed, no save, no welcome)")
        void alreadyVerified() {
            User user = activeUser();
            user.setVerified(true);
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));

            service.verifyEmail("raw-token");

            verify(redisTemplate).delete(anyString());
            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendWelcomeEmail(any(), any(), any());
        }

        @Test
        @DisplayName("blank token → TM_403")
        void blankToken() {
            assertThatThrownBy(() -> service.verifyEmail(" "))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_403"));
        }

        @Test
        @DisplayName("token not in Redis → TM_403")
        void tokenMiss() {
            when(valueOps.get(anyString())).thenReturn(null);

            assertThatThrownBy(() -> service.verifyEmail("raw-token"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_403"));
        }

        @Test
        @DisplayName("user for the token missing → TM_403")
        void userMissing() {
            when(valueOps.get(anyString())).thenReturn(USER_UUID.toString());
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyEmail("raw-token"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_403"));
        }

        @Test
        @DisplayName("stored value not a UUID → TM_403")
        void badUuid() {
            when(valueOps.get(anyString())).thenReturn("bogus");

            assertThatThrownBy(() -> service.verifyEmail("raw-token"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_403"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resendVerificationEmail")
    class ResendVerificationEmail {

        @Test
        @DisplayName("unverified user → verification email re-sent")
        void resends() {
            User user = activeUser();
            user.setVerified(false);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

            service.resendVerificationEmail(user);

            verify(emailService).sendVerificationEmail(eq("alice@example.com"), any(), anyString(), eq(1440L));
        }

        @Test
        @DisplayName("already verified → TM_404")
        void alreadyVerified() {
            User user = activeUser();
            user.setVerified(true);

            assertThatThrownBy(() -> service.resendVerificationEmail(user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_404"));
        }

        @Test
        @DisplayName("guest → silent no-op")
        void guestNoop() {
            User guest = activeUser();
            guest.setGuest(true);

            service.resendVerificationEmail(guest);

            verify(emailService, never()).sendVerificationEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("null principal → silent no-op")
        void nullNoop() {
            service.resendVerificationEmail(null);

            verify(emailService, never()).sendVerificationEmail(any(), any(), any(), anyLong());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        private ChangePasswordRequest req(String current, String next) {
            ChangePasswordRequest r = new ChangePasswordRequest();
            r.setCurrentPassword(current);
            r.setNewPassword(next);
            return r;
        }

        @Test
        @DisplayName("correct current password → hash updated, all tokens revoked")
        void happyPath() {
            User user = activeUser();
            when(passwordEncoder.matches("old", "$2a$hash")).thenReturn(true);
            when(pwnedPasswordService.isBreached("N3wPass!")).thenReturn(false);
            when(passwordEncoder.encode("N3wPass!")).thenReturn("$new");

            service.changePassword(req("old", "N3wPass!"), user);

            assertThat(user.getPasswordHash()).isEqualTo("$new");
            verify(userRepository).save(user);
            verify(refreshTokenRepository).revokeAllUserTokens(user);
        }

        @Test
        @DisplayName("wrong current password → TM_042")
        void wrongCurrent() {
            User user = activeUser();
            when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword(req("bad", "N3wPass!"), user))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_042"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("breached new password → TM_496")
        void breachedNew() {
            User user = activeUser();
            when(passwordEncoder.matches("old", "$2a$hash")).thenReturn(true);
            when(pwnedPasswordService.isBreached("N3wPass!")).thenReturn(true);

            assertThatThrownBy(() -> service.changePassword(req("old", "N3wPass!"), user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_496"));
            verify(userRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("requestAccountDeletion")
    class RequestAccountDeletion {

        @Test
        @DisplayName("valid password → account soft-deleted, timer set, tokens revoked")
        void happyPath() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pw", "$2a$hash")).thenReturn(true);

            service.requestAccountDeletion(user, "pw");

            assertThat(user.isDeleted()).isTrue();
            assertThat(user.getDeletionRequestedAt()).isNotNull();
            verify(userRepository).save(user);
            verify(refreshTokenRepository).revokeAllUserTokens(user);
        }

        @Test
        @DisplayName("OAuth-only account (no password hash) skips password confirmation")
        void oauthNoPasswordRequired() {
            User user = activeUser();
            user.setPasswordHash(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            service.requestAccountDeletion(user, null);

            assertThat(user.isDeleted()).isTrue();
            verify(refreshTokenRepository).revokeAllUserTokens(user);
        }

        @Test
        @DisplayName("guest account → TM_029")
        void guestRejected() {
            User guest = activeUser();
            guest.setGuest(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> service.requestAccountDeletion(guest, "pw"))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_029"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("already pending deletion → idempotent no-op")
        void alreadyDeleted() {
            User user = activeUser();
            user.setDeleted(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            service.requestAccountDeletion(user, "pw");

            verify(userRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeAllUserTokens(any());
        }

        @Test
        @DisplayName("wrong password on a local account → TM_497")
        void wrongPassword() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);

            assertThatThrownBy(() -> service.requestAccountDeletion(user, "bad"))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_497"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("null password on a local account → TM_497")
        void nullPassword() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.requestAccountDeletion(user, null))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_497"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("purgeExpiredDeletedAccounts")
    class PurgeExpiredDeletedAccounts {

        @Test
        @DisplayName("due accounts are anonymized and the purged count is returned")
        void purgesDue() {
            User a = activeUser();
            a.setId(10L);
            a.setUuid(UUID.randomUUID());
            User b = activeUser();
            b.setId(11L);
            b.setUuid(UUID.randomUUID());
            when(userRepository.findAccountsDueForPurge(any(Instant.class))).thenReturn(List.of(a, b));

            int purged = service.purgeExpiredDeletedAccounts();

            assertThat(purged).isEqualTo(2);
            verify(refreshTokenRepository).revokeAllUserTokens(a);
            verify(sessionRepository).deleteByUser(a);
            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            User scrubbed = cap.getAllValues().get(0);
            assertThat(scrubbed.getUsername()).startsWith("deleted_");
            assertThat(scrubbed.getEmail()).isNull();
            assertThat(scrubbed.getPasswordHash()).isNull();
            assertThat(scrubbed.getName()).isEqualTo("Deleted User");
            assertThat(scrubbed.getDeletionRequestedAt()).isNull();
        }

        @Test
        @DisplayName("nothing due → returns 0")
        void nothingDue() {
            when(userRepository.findAccountsDueForPurge(any(Instant.class))).thenReturn(List.of());

            assertThat(service.purgeExpiredDeletedAccounts()).isZero();
        }

        @Test
        @DisplayName("one failing row is isolated — the rest still purge")
        void partialFailureIsolated() {
            User bad = activeUser();
            bad.setId(10L);
            bad.setUuid(UUID.randomUUID());
            User good = activeUser();
            good.setId(11L);
            good.setUuid(UUID.randomUUID());
            when(userRepository.findAccountsDueForPurge(any(Instant.class))).thenReturn(List.of(bad, good));
            doThrow(new RuntimeException("db blip")).when(sessionRepository).deleteByUser(bad);

            int purged = service.purgeExpiredDeletedAccounts();

            assertThat(purged).isEqualTo(1);
            verify(sessionRepository).deleteByUser(good);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("existing user → mapped response enriched with features")
        void happyPath() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            AuthUserResponse mapped = new AuthUserResponse();
            when(userMapper.toAuthUserResponse(user)).thenReturn(mapped);
            when(featureAccessService.effectiveWireNames(user)).thenReturn(Set.of("chat", "match"));

            AuthUserResponse out = service.getCurrentUser(user);

            assertThat(out).isSameAs(mapped);
            assertThat(out.getFeatures()).containsExactlyInAnyOrder("chat", "match");
        }

        @Test
        @DisplayName("user no longer exists → TM_024")
        void notFound() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCurrentUser(user))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("provided fields are applied; null fields are left untouched")
        void appliesProvidedFields() {
            User user = activeUser();
            user.setName("Old Name");
            user.setBio("old bio");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userMapper.toAuthUserResponse(user)).thenReturn(new AuthUserResponse());
            when(featureAccessService.effectiveWireNames(user)).thenReturn(Set.of());

            UpdateProfileRequest r = UpdateProfileRequest.builder()
                    .name("New Name")
                    .city("Pune")
                    .phone("+9199999")
                    .build();

            service.updateProfile(r, user);

            assertThat(user.getName()).isEqualTo("New Name");
            assertThat(user.getCity()).isEqualTo("Pune");
            assertThat(user.getMobileNumber()).isEqualTo("+9199999");
            assertThat(user.getBio()).isEqualTo("old bio"); // untouched
            verify(featureAccessCache).evict(1L);
            verify(reputationRecorder, never()).record(anyLong(), any(), any());
        }

        @Test
        @DisplayName("reaching 100% completion records the PROFILE_COMPLETED reputation event")
        void recordsReputationAt100() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userMapper.toAuthUserResponse(user)).thenReturn(new AuthUserResponse());
            when(featureAccessService.effectiveWireNames(user)).thenReturn(Set.of());

            UpdateProfileRequest r = UpdateProfileRequest.builder()
                    .profileImage("http://img")
                    .bio("a full bio")
                    .interests(Set.of(Interest.SPORTS, Interest.MUSIC, Interest.MOVIES))
                    .mood(Mood.FLIRT)
                    .conversationEnergy(ConversationEnergy.FRIENDLY)
                    .languages(Set.of(Language.EN))
                    .lookingFor(Set.of(LookingForTag.FRIENDS))
                    .voiceIntroUrl("http://voice")
                    .build();

            service.updateProfile(r, user);

            assertThat(user.getProfileCompletion()).isEqualTo(100);
            verify(reputationRecorder).record(1L, ReputationEventType.PROFILE_COMPLETED, "1");
        }

        @Test
        @DisplayName("user no longer exists → TM_024")
        void notFound() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateProfile(UpdateProfileRequest.builder().build(), user))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }

        @Test
        @DisplayName("remaining scalar fields (country/mobile/age/occupation/education/voiceDuration) applied")
        void appliesRemainingScalarFields() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userMapper.toAuthUserResponse(user)).thenReturn(new AuthUserResponse());
            when(featureAccessService.effectiveWireNames(user)).thenReturn(Set.of());

            UpdateProfileRequest r = UpdateProfileRequest.builder()
                    .country("Norway")
                    .mobileNumber("+4711111")
                    .age(33)
                    .occupation("Engineer")
                    .education("MSc")
                    .voiceIntroDurationMs(4200)
                    .build();

            service.updateProfile(r, user);

            assertThat(user.getCountry()).isEqualTo("Norway");
            assertThat(user.getMobileNumber()).isEqualTo("+4711111");
            assertThat(user.getAge()).isEqualTo(33);
            assertThat(user.getOccupation()).isEqualTo("Engineer");
            assertThat(user.getEducation()).isEqualTo("MSc");
            assertThat(user.getVoiceIntroDurationMs()).isEqualTo(4200);
        }

        @Test
        @DisplayName("personality map: valid entries clamped to 0..100; null key/value entries skipped")
        void personalityClampedAndFiltered() {
            User user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userMapper.toAuthUserResponse(user)).thenReturn(new AuthUserResponse());
            when(featureAccessService.effectiveWireNames(user)).thenReturn(Set.of());

            Map<PersonalityTrait, Integer> traits = new HashMap<>();
            traits.put(PersonalityTrait.OPENNESS, 150);          // clamped down to 100
            traits.put(PersonalityTrait.CONSCIENTIOUSNESS, -5);  // clamped up to 0
            traits.put(null, 50);                                // null key → skipped
            traits.put(PersonalityTrait.EXTRAVERSION, null);     // null value → skipped

            service.updateProfile(UpdateProfileRequest.builder().personality(traits).build(), user);

            assertThat(user.getPersonality())
                    .containsEntry(PersonalityTrait.OPENNESS, 100)
                    .containsEntry(PersonalityTrait.CONSCIENTIOUSNESS, 0)
                    .doesNotContainKey(PersonalityTrait.EXTRAVERSION)
                    .doesNotContainKey(null);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Phase-7 branch backfill: negative / edge paths for the login pipeline, country
    // backfill sub-conditions, OAuth field-merge branches, anti-enumeration silent
    // returns, cooldown fail-open, and the pure device/username helpers.
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("login (branch backfill)")
    class LoginBranches {

        private LoginRequest req(String id, String pw) {
            LoginRequest r = new LoginRequest();
            r.setEmail(id);
            r.setPassword(pw);
            return r;
        }

        @Test
        @DisplayName("null email → identifier normalized to \"\" → unknown user TM_024 + failure recorded")
        void nullEmailIdentifier() {
            when(userRepository.findByUsernameIgnoreCase("")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(req(null, "pw"), "UA", "1.2.3.4", httpRequest()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
            verify(loginAttemptService).recordFailure("", "1.2.3.4");
        }

        @Test
        @DisplayName("blank (non-null) country string is backfilled from detection")
        void backfillsCountryWhenBlankString() {
            User user = activeUser();
            user.setCountry("");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isEqualTo("India");
        }

        @Test
        @DisplayName("detected country \"Unknown\" is not backfilled")
        void detectedUnknownNotBackfilled() {
            User user = activeUser();
            user.setCountry(null);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly("Unknown"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isNull();
        }

        @Test
        @DisplayName("detected country blank is not backfilled")
        void detectedBlankNotBackfilled() {
            User user = activeUser();
            user.setCountry(null);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly("  "));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isNull();
        }

        @Test
        @DisplayName("detected country null is not backfilled")
        void detectedNullNotBackfilled() {
            User user = activeUser();
            user.setCountry(null);
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly(null));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getCountry()).isNull();
        }

        @Test
        @DisplayName("login-alert opt-out (emailLoginAlerts=false) → no alert email")
        void loginAlertSuppressedWhenDisabled() {
            User user = activeUser();
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(userSettingRepository.findByUser(user))
                    .thenReturn(Optional.of(UserSetting.builder().emailLoginAlerts(false).build()));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            verify(emailService, never()).sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("null email on the account → alert skipped (no NPE)")
        void loginAlertSkippedWhenEmailNull() {
            User user = activeUser();
            user.setEmail(null);
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            verify(emailService, never()).sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("blank email on the account → alert skipped")
        void loginAlertSkippedWhenEmailBlank() {
            User user = activeUser();
            user.setEmail("");
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            verify(emailService, never()).sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("alert-email failure is swallowed — login still succeeds")
        void loginAlertExceptionSwallowed() {
            User user = activeUser();
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            doThrow(new RuntimeException("mail down")).when(emailService)
                    .sendLoginAlertEmail(any(), any(), any(), any(), any(), any(), any());
            stubLoginPipeline();

            LoginResponse resp = service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
        }

        @Test
        @DisplayName("blank client IP → last-login IP not recorded")
        void ipBlankSkipsLastLoginIp() {
            User user = activeUser();
            user.setCountry("Canada");
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(
                    CountryDetectionResult.builder().country("India").source("GeoIP").clientIp("")
                            .city("Pune").region("Maharashtra").build());
            stubLoginPipeline();

            service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(user.getLastLoginIp()).isNull();
        }

        @Test
        @DisplayName("push-subscription sweep failure is swallowed — login still succeeds")
        void pushSweepFailureSwallowed() {
            User user = activeUser();
            when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(true);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            doThrow(new RuntimeException("push down")).when(webPushService).removeAllSubscriptionsForUser(1L);
            stubLoginPipeline();

            LoginResponse resp = service.login(req("alice", "pw"), "UA", "1.2.3.4", httpRequest());

            assertThat(resp.getTokens().getAccessToken()).isEqualTo("access-jwt");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("signup referral / verification (branch backfill)")
    class SignupReferralBranches {

        private SignupRequest req(String referrer) {
            SignupRequest r = new SignupRequest();
            r.setName("New User");
            r.setUsername("newuser");
            r.setEmail("New@Example.com");
            r.setPassword("Sup3rStr0ng!");
            r.setAge(25);
            r.setGender("male");
            r.setReferredByUsername(referrer);
            return r;
        }

        private void stubSignupBase() {
            when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
            when(pwnedPasswordService.isBreached(any())).thenReturn(false);
            when(moderationService.moderateText(any())).thenReturn(ModerationResult.clean());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(valueOps.setIfAbsent(anyString(), any(), any())).thenReturn(true);
            stubLoginPipeline();
        }

        private User savedUser() {
            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("blank referrer username → no referrer attributed")
        void blankReferrerIgnored() {
            stubSignupBase();

            service.signup(req("   "), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("referrer of only '@' → empty after strip → no referrer")
        void atOnlyReferrerIgnored() {
            stubSignupBase();

            service.signup(req("@"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("self-referral (referrer == own username) → ignored")
        void selfReferralIgnored() {
            stubSignupBase();

            service.signup(req("newuser"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("guest referrer is filtered out")
        void guestReferrerIgnored() {
            stubSignupBase();
            User referrer = activeUser();
            referrer.setUsername("sponsor");
            referrer.setGuest(true);
            when(userRepository.findByUsernameIgnoreCase("sponsor")).thenReturn(Optional.of(referrer));

            service.signup(req("@sponsor"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("banned referrer is filtered out")
        void bannedReferrerIgnored() {
            stubSignupBase();
            User referrer = activeUser();
            referrer.setUsername("sponsor");
            referrer.setBanned(true);
            when(userRepository.findByUsernameIgnoreCase("sponsor")).thenReturn(Optional.of(referrer));

            service.signup(req("@sponsor"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("soft-deleted referrer is filtered out")
        void deletedReferrerIgnored() {
            stubSignupBase();
            User referrer = activeUser();
            referrer.setUsername("sponsor");
            referrer.setDeleted(true);
            when(userRepository.findByUsernameIgnoreCase("sponsor")).thenReturn(Optional.of(referrer));

            service.signup(req("@sponsor"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("referrer lookup throwing is swallowed — signup still succeeds with no referrer")
        void referrerLookupExceptionIgnored() {
            stubSignupBase();
            when(userRepository.findByUsernameIgnoreCase("sponsor"))
                    .thenThrow(new RuntimeException("db blip"));

            service.signup(req("@sponsor"), "UA", httpRequest());

            assertThat(savedUser().getReferredBy()).isNull();
        }

        @Test
        @DisplayName("null request email → verification email skipped (blank/null recipient guard)")
        void nullEmailSkipsVerificationEmail() {
            SignupRequest r = req(null);
            r.setEmail(null);
            when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
            when(pwnedPasswordService.isBreached(any())).thenReturn(false);
            when(moderationService.moderateText(any())).thenReturn(ModerationResult.clean());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.signup(r, "UA", httpRequest());

            verify(emailService, never()).sendVerificationEmail(any(), any(), any(), anyLong());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("oauthLogin (branch backfill)")
    class OauthBranches {

        /**
         * Existing account, pre-populated so no field is dirty unless the test says so.
         */
        private User cleanExisting() {
            User u = activeUser();
            u.setGoogleId("g-sub-1");
            u.setProfileImage("http://have");
            u.setAge(40);
            u.setGender("male");
            u.setVerified(true);
            u.setCountry("Canada");
            return u;
        }

        private OAuthUserInfo.OAuthUserInfoBuilder infoFor(User ignored) {
            return OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("alice@example.com").emailVerified(true)
                    .name("Alice").picture("http://have").age(40).gender("male");
        }

        @Test
        @DisplayName("blank OAuth email is treated as null (matched by provider id)")
        void blankOauthEmailTreatedAsNull() {
            User existing = cleanExisting();
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("   ").emailVerified(true)
                    .name("Alice").picture("http://have").age(40).gender("male").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("null provider id → matched by email, google id not linked")
        void matchByEmailWhenProviderIdNull() {
            User existing = cleanExisting();
            existing.setGoogleId(null);
            OAuthUserInfo info = infoFor(existing).providerId(null).build();
            when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.getGoogleId()).isNull();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("blank provider id → matched by email")
        void matchByEmailWhenProviderIdBlank() {
            User existing = cleanExisting();
            existing.setGoogleId(null);
            OAuthUserInfo info = infoFor(existing).providerId("").build();
            when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            // providerId "" is non-null, so googleId is linked to the blank value (dirty save).
            assertThat(existing.getGoogleId()).isEmpty();
            verify(userRepository, times(2)).save(existing);
        }

        @Test
        @DisplayName("new user with null display name → defaults to \"User\"")
        void provisionsWithDefaultNameWhenNameNull() {
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("new@gmail.com").emailVerified(true).name(null).build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getName()).isEqualTo("User");
        }

        @Test
        @DisplayName("new user with blank display name → defaults to \"User\"")
        void provisionsWithDefaultNameWhenNameBlank() {
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("new@gmail.com").emailVerified(true).name("   ").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(2)).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getName()).isEqualTo("User");
        }

        @Test
        @DisplayName("create race resolved via the email lookup in the recovery '.or()'")
        void createRaceReusesRowByEmail() {
            User winner = activeUser();
            OAuthUserInfo info = OAuthUserInfo.builder()
                    .providerId("g-sub-1").email("new@gmail.com").emailVerified(true).name("N").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("new@gmail.com"))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("dup"))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            verify(emailService, never()).sendWelcomeEmail(any(), any(), any());
        }

        @Test
        @DisplayName("blank existing profile image is backfilled from Google picture")
        void backfillProfileImageWhenBlank() {
            User existing = cleanExisting();
            existing.setProfileImage("");
            OAuthUserInfo info = infoFor(existing).picture("http://newpic").build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.getProfileImage()).isEqualTo("http://newpic");
        }

        @Test
        @DisplayName("null Google picture → profile image left untouched")
        void skipProfileImageWhenPictureNull() {
            User existing = cleanExisting();
            existing.setProfileImage(null);
            OAuthUserInfo info = infoFor(existing).picture(null).build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.getProfileImage()).isNull();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("blank existing gender + null Google gender → gender untouched")
        void skipGenderWhenInfoGenderNull() {
            User existing = cleanExisting();
            existing.setGender("");
            OAuthUserInfo info = infoFor(existing).gender(null).build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.getGender()).isEmpty();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("unverified existing + Google not-verified → stays unverified")
        void skipVerifiedWhenInfoNotVerified() {
            User existing = cleanExisting();
            existing.setVerified(false);
            OAuthUserInfo info = infoFor(existing).emailVerified(false).build();
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(info, "UA", httpRequest());

            assertThat(existing.isVerified()).isFalse();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("blank existing country is backfilled from detection")
        void backfillCountryWhenBlank() {
            User existing = cleanExisting();
            existing.setCountry("");
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detection("India"));
            stubLoginPipeline();

            service.oauthLogin(infoFor(existing).build(), "UA", httpRequest());

            assertThat(existing.getCountry()).isEqualTo("India");
        }

        @Test
        @DisplayName("detected \"Unknown\" country → not backfilled")
        void skipCountryWhenDetectedUnknown() {
            User existing = cleanExisting();
            existing.setCountry(null);
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly("Unknown"));
            stubLoginPipeline();

            service.oauthLogin(infoFor(existing).build(), "UA", httpRequest());

            assertThat(existing.getCountry()).isNull();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("detected blank country → not backfilled")
        void skipCountryWhenDetectedBlank() {
            User existing = cleanExisting();
            existing.setCountry(null);
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly("  "));
            stubLoginPipeline();

            service.oauthLogin(infoFor(existing).build(), "UA", httpRequest());

            assertThat(existing.getCountry()).isNull();
            verify(userRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("detected null country → not backfilled")
        void skipCountryWhenDetectedNull() {
            User existing = cleanExisting();
            existing.setCountry(null);
            when(userRepository.findByGoogleId("g-sub-1")).thenReturn(Optional.of(existing));
            when(countryDetectionService.detectCountry(any())).thenReturn(detectionOnly(null));
            stubLoginPipeline();

            service.oauthLogin(infoFor(existing).build(), "UA", httpRequest());

            assertThat(existing.getCountry()).isNull();
            verify(userRepository, times(1)).save(existing);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resendVerificationEmail (branch backfill)")
    class ResendVerificationBranches {

        @Test
        @DisplayName("blank email → send skipped (recipient guard in sendVerificationEmail)")
        void blankEmailSkipsSend() {
            User user = activeUser();
            user.setVerified(false);
            user.setEmail("");

            service.resendVerificationEmail(user);

            verify(emailService, never()).sendVerificationEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("cooldown active → verification email suppressed")
        void cooldownActiveSkipsSend() {
            User user = activeUser();
            user.setVerified(false);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

            service.resendVerificationEmail(user);

            verify(emailService, never()).sendVerificationEmail(any(), any(), any(), anyLong());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("forgotPassword cooldown / recipient (branch backfill)")
    class ForgotPasswordCooldownBranches {

        private ForgotPasswordRequest req(String email) {
            ForgotPasswordRequest r = new ForgotPasswordRequest();
            r.setEmail(email);
            return r;
        }

        @Test
        @DisplayName("null email on the resolved account → cooldown passes, reset still sent")
        void nullRecipientEmailPassesCooldown() {
            User user = activeUser();
            user.setEmail(null);
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));

            service.forgotPassword(req("alice@example.com"));

            verify(emailService).sendPasswordResetEmail(any(), any(), anyString(), eq(30L));
        }

        @Test
        @DisplayName("blank email on the resolved account → cooldown passes, reset still sent")
        void blankRecipientEmailPassesCooldown() {
            User user = activeUser();
            user.setEmail("");
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));

            service.forgotPassword(req("alice@example.com"));

            verify(emailService).sendPasswordResetEmail(any(), any(), anyString(), eq(30L));
        }

        @Test
        @DisplayName("Redis failure in cooldown check fails open → reset still sent")
        void cooldownRedisFailureFailsOpen() {
            User user = activeUser();
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenThrow(new RuntimeException("redis down"));

            service.forgotPassword(req("alice@example.com"));

            verify(emailService).sendPasswordResetEmail(eq("alice@example.com"), any(), anyString(), eq(30L));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("token/state guard branches (backfill)")
    class MiscBranchCoverage {

        @Test
        @DisplayName("resetPassword: null token → TM_038")
        void resetPasswordNullToken() {
            ResetPasswordRequest r = new ResetPasswordRequest();
            r.setToken(null);
            r.setPassword("NewP4ss!");

            assertThatThrownBy(() -> service.resetPassword(r))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_038"));
        }

        @Test
        @DisplayName("verifyEmail: null token → TM_403")
        void verifyEmailNullToken() {
            assertThatThrownBy(() -> service.verifyEmail(null))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_403"));
        }

        @Test
        @DisplayName("forgotPassword: null email → normalized to \"\" → silent no-op")
        void forgotPasswordNullEmail() {
            ForgotPasswordRequest r = new ForgotPasswordRequest();
            r.setEmail(null);
            when(userRepository.findByEmailIgnoreCase("")).thenReturn(Optional.empty());

            service.forgotPassword(r);

            verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("refresh: session with matching IP but different user-agent is not bumped")
        void refreshSessionUserAgentMismatch() {
            User user = activeUser();
            RefreshToken token = RefreshToken.builder()
                    .user(user).token("old-token")
                    .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                    .revoked(false).build();
            Session s = Session.builder().user(user).ipAddress("1.2.3.4").userAgent("Other").build();
            when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(token));
            when(tokenProvider.generateToken(any(), anyBoolean())).thenReturn("new-access");
            when(sessionRepository.findByUserAndIsDeletedFalse(user)).thenReturn(List.of(s));

            service.refresh("old-token", "UA", "1.2.3.4");

            verify(sessionRepository, never()).save(s);
        }

        @Test
        @DisplayName("requestAccountDeletion: blank password hash → password check skipped")
        void requestAccountDeletionBlankHash() {
            User user = activeUser();
            user.setPasswordHash("");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            service.requestAccountDeletion(user, null);

            assertThat(user.isDeleted()).isTrue();
            verify(refreshTokenRepository).revokeAllUserTokens(user);
        }

        @Test
        @DisplayName("purge: account with null interests set → anonymized without clearing")
        void purgeAccountWithNullInterests() {
            User u = activeUser();
            u.setId(10L);
            u.setUuid(UUID.randomUUID());
            u.setInterests(null);
            when(userRepository.findAccountsDueForPurge(any(Instant.class))).thenReturn(List.of(u));

            int purged = service.purgeExpiredDeletedAccounts();

            assertThat(purged).isEqualTo(1);
            assertThat(u.getInterests()).isNull();
            assertThat(u.getUsername()).startsWith("deleted_");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateUniqueUsername (direct)")
    class GenerateUniqueUsernameDirect {

        private String gen(String email, String name) {
            return (String) ReflectionTestUtils.invokeMethod(service, "generateUniqueUsername", email, name);
        }

        @Test
        @DisplayName("email without '@' → base derived from the display name")
        void fromNameWhenEmailHasNoAt() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);

            assertThat(gen("noatsign", "Bob")).isEqualTo("bob");
        }

        @Test
        @DisplayName("all-symbol local part strips to blank → falls back to 'user'")
        void fallsBackToUserWhenStripEmpty() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);

            assertThat(gen("###@x.com", "Bob")).isEqualTo("user");
        }

        @Test
        @DisplayName("over-long base is truncated to 40 chars")
        void truncatesLongBase() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            String local = "a".repeat(45);

            String out = gen(local + "@x.com", "Bob");

            assertThat(out).hasSize(40).isEqualTo("a".repeat(40));
        }

        @Test
        @DisplayName("collision on the base → suffixed candidate is generated")
        void suffixesOnCollision() {
            when(userRepository.existsByUsername(anyString())).thenReturn(true, false);

            String out = gen("taken@x.com", "Bob");

            assertThat(out).startsWith("taken_").isNotEqualTo("taken");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("friendlyDevice (direct)")
    class FriendlyDeviceDirect {

        private String friendly(String userAgent) {
            return (String) ReflectionTestUtils.invokeMethod(service, "friendlyDevice", userAgent);
        }

        static Stream<Arguments> deviceCases() {
            String longUa = "z".repeat(70);
            return Stream.of(
                    Arguments.of(null, null),
                    Arguments.of("", null),
                    Arguments.of("   ", null),
                    Arguments.of("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537", "Chrome on Windows"),
                    Arguments.of("Mozilla/5.0 (iPhone) Safari/537", "Safari on iPhone"),
                    Arguments.of("Mozilla/5.0 (iPad) Safari/537", "Safari on iPad"),
                    Arguments.of("Mozilla/5.0 (Android) Firefox/120", "Firefox on Android"),
                    Arguments.of("Mozilla/5.0 (Macintosh) Safari/537", "Safari on Mac"),
                    Arguments.of("Mozilla/5.0 (Mac OS X) Safari/537", "Safari on Mac"),
                    Arguments.of("Mozilla/5.0 (X11; Linux) Firefox/120", "Firefox on Linux"),
                    Arguments.of("Mozilla/5.0 (Windows NT 10.0) Edg/120", "Edge on Windows"),
                    Arguments.of("Mozilla/5.0 (Windows NT 10.0) OPR/100", "Opera on Windows"),
                    Arguments.of("Mozilla/5.0 (Windows NT 10.0) Opera/100", "Opera on Windows"),
                    Arguments.of("Mozilla/5.0 (Windows NT 10.0)", "Windows"),
                    Arguments.of("Firefox/120", "Firefox"),
                    Arguments.of("RandomAgent/1.0", "RandomAgent/1.0"),
                    Arguments.of(longUa, longUa.substring(0, 60) + "…")
            );
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
        @MethodSource("deviceCases")
        @DisplayName("maps User-Agent to a friendly device label across the OS/browser matrix")
        void mapsUserAgent(String userAgent, String expected) {
            assertThat(friendly(userAgent)).isEqualTo(expected);
        }
    }
}
