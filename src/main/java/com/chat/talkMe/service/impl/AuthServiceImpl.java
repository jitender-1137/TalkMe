package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.RefreshToken;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.Session;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.ChangePasswordRequest;
import com.chat.talkMe.dto.request.ForgotPasswordRequest;
import com.chat.talkMe.dto.request.GuestLoginRequest;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.ResetPasswordRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.mapper.SessionMapper;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.PermissionRepository;
import com.chat.talkMe.repository.RefreshTokenRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.SessionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.EmailService;
import com.chat.talkMe.dto.response.CountryDetectionResult;
import com.chat.talkMe.service.CountryDetectionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final CountryDetectionService countryDetectionService;
    private final com.chat.talkMe.service.LoginAttemptService loginAttemptService;
    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final com.chat.talkMe.service.WebPushService webPushService;

    @Value("${security.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${security.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${security.jwt.guest-refresh-token-expiration-ms}")
    private long guestRefreshTokenExpirationMs;

    @Value("${app.auth.password-reset-token-ttl-minutes:30}")
    private long passwordResetTtlMinutes;

    @Value("${app.auth.account-deletion-window-days:30}")
    private long accountDeletionWindowDays;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /** Redis key prefix for one-time password-reset tokens (value = user UUID). */
    private static final String PWRESET_KEY_PREFIX = "pwreset:token:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent, String ip, HttpServletRequest httpRequest) {
        String identifier = request.getEmail();

        // Brute-force guard: reject if this account/IP is locked out.
        loginAttemptService.assertNotBlocked(identifier, ip);

        User user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .orElse(null);

        if (user == null) {
            loginAttemptService.recordFailure(identifier, ip);
            throw new UnauthorizedException("Invalid username or password", "TM_024");
        }

        if (user.isGuest()) {
            throw new ForbiddenException("Guest accounts must use Guest Login flow", "TM_029");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(identifier, ip);
            throw new UnauthorizedException("Invalid username or password", "TM_024");
        }

        loginAttemptService.recordSuccess(identifier, ip);

        // Account-deletion recovery: a soft-deleted account is automatically restored
        // when the owner logs back in within the recovery window. Past the window the
        // account is pending permanent purge and must not be resurrected.
        if (user.isDeleted()) {
            Instant requestedAt = user.getDeletionRequestedAt();
            boolean withinWindow = requestedAt != null
                    && Instant.now().isBefore(requestedAt.plus(Duration.ofDays(accountDeletionWindowDays)));
            if (withinWindow) {
                user.setDeleted(false);
                user.setDeletionRequestedAt(null);
                userRepository.save(user);
                log.info("Account '{}' restored on login (was pending deletion)", user.getUsername());
            } else {
                throw new UnauthorizedException("This account has been deleted.", "TM_024");
            }
        }

        // Backfill country from IP on login ONLY when the user has none set.
        // An existing country is never overwritten. Best-effort: a detection
        // failure must not block login.
        if (user.getCountry() == null || user.getCountry().isBlank()) {
            try {
                CountryDetectionResult detection = countryDetectionService.detectCountry(httpRequest);
                String detected = detection != null ? detection.getCountry() : null;
                if (detected != null && !detected.isBlank() && !"Unknown".equalsIgnoreCase(detected)) {
                    user.setCountry(detected);
                    userRepository.save(user);
                    log.info("Backfilled country '{}' for {} on login (source: {}, IP: {})",
                            detected, identifier, detection.getSource(), detection.getClientIp());
                }
            } catch (Exception e) {
                log.warn("Country backfill on login failed for {}: {}", identifier, e.getMessage());
            }
        }

        return generateLoginResponse(user, userAgent, ip);
    }

    @Override
    @Transactional
    public LoginResponse signup(SignupRequest request, String userAgent, HttpServletRequest httpRequest) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("TM_047");
        }

        // Generate username from email prefix
        String username = request.getUsername();

        Role userRole = getOrCreateRole("ROLE_USER");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(httpRequest);

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isGuest(false)
                .isVerified(false)
                .roles(Set.of(userRole))
                .age(request.getAge())
                .gender(request.getGender())
                .country(detectionResult.getCountry())
                .build();

        user = userRepository.save(user);
        log.info("User registered successfully: {}. Country detected: {} (Source: {}, IP: {})", 
                username, detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp());

        return generateLoginResponse(user, userAgent, detectionResult.getClientIp());
    }

    @Override
    @Transactional
    public LoginResponse loginAsGuest(GuestLoginRequest request, String userAgent, HttpServletRequest httpRequest) {
        String username = "guest_" + UUID.randomUUID().toString().substring(0, 8);
        Role guestRole = getOrCreateRole("ROLE_GUEST");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(httpRequest);

        User guest = User.builder()
                .name(request.getName())
                .username(username)
                .age(request.getAge())
                .gender(request.getGender())
                .isGuest(true)
                .isVerified(true)
                .roles(Set.of(guestRole))
                .country(detectionResult.getCountry())
                .build();

        guest = userRepository.save(guest);
        log.info("Guest user logged in: {}. Country detected: {} (Source: {}, IP: {})", 
                username, detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp());

        return generateLoginResponse(guest, userAgent, detectionResult.getClientIp());
    }

    @Override
    @Transactional
    public JwtTokensResponse refresh(String tokenStr, String userAgent, String ip) {
        RefreshToken token = refreshTokenRepository.findByToken(tokenStr)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token", "TM_026"));

        User user = token.getUser();

        // Single-device policy: a revoked token means this device was superseded —
        // either the user signed in on another device (which revokes prior tokens),
        // or this exact token was already rotated. Either way it's no longer valid,
        // so reject with 401 and let this device clear its state + show the login
        // page. We deliberately DO NOT revoke all sessions here: that would also
        // log out the device that currently holds the valid token (and turned a
        // benign refresh race into a logout storm).
        if (token.isRevoked() || token.isExpired()) {
            throw new UnauthorizedException(
                    "Session expired or signed in on another device. Please log in again.", "TM_026");
        }

        // Invalidate old token and replace
        token.setRevoked(true);
        
        // Generate new access token
        String newAccessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest());
        
        // Generate rotated refresh token
        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;
        String newRefreshTokenStr = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .token(newRefreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        token.setReplacedByToken(newRefreshTokenStr);
        refreshTokenRepository.save(token);
        refreshTokenRepository.save(newRefreshToken);

        // Update session active timestamp
        List<Session> sessions = sessionRepository.findByUserAndIsDeletedFalse(user);
        for (Session session : sessions) {
            if (ip.equals(session.getIpAddress()) && userAgent.equals(session.getUserAgent())) {
                session.setLastActiveAt(Instant.now());
                sessionRepository.save(session);
            }
        }

        return JwtTokensResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshTokenStr)
                .expiresIn(accessTokenExpirationMs / 1000)
                .build();
    }

    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        RefreshToken token = refreshTokenRepository.findByToken(refreshTokenStr).orElse(null);
        if (token != null) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            log.info("Logout successful for user: {}", token.getUser().getUsername());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionResponse> getSessions(User currentUser) {
        return sessionRepository.findByUserAndIsDeletedFalse(currentUser).stream()
                .map(sessionMapper::toSessionResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void revokeSession(String sessionUuid, User currentUser) {
        Session session = sessionRepository.findByUuid(UUID.fromString(sessionUuid))
                .orElseThrow(() -> new NotFoundException("Session not found", "TM_053"));

        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot revoke session of another user", "TM_103");
        }

        session.setDeleted(true);
        sessionRepository.save(session);
    }

    @Override
    @Transactional
    public void revokeAllSessions(User currentUser) {
        List<Session> sessions = sessionRepository.findByUserAndIsDeletedFalse(currentUser);
        for (Session session : sessions) {
            if (!session.isCurrent()) {
                session.setDeleted(true);
                sessionRepository.save(session);
            }
        }
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        // Never reveal whether the email exists (anti-enumeration): always return
        // success to the controller. Only real, non-guest, active accounts get a link.
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user == null || user.isGuest() || user.isDeleted()) {
            return;
        }

        // Cryptographically-strong, single-use token stored in Redis with a short TTL.
        String token = generateSecureToken();
        redisTemplate.opsForValue().set(
                PWRESET_KEY_PREFIX + token,
                user.getUuid().toString(),
                Duration.ofMinutes(passwordResetTtlMinutes));

        String resetLink = frontendBaseUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetLink, passwordResetTtlMinutes);
        log.info("Password reset requested for user '{}'", user.getUsername());
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String key = PWRESET_KEY_PREFIX + request.getToken();
        String userUuid = redisTemplate.opsForValue().get(key);
        if (userUuid == null) {
            throw new UnauthorizedException("Reset token invalid or expired", "TM_038");
        }

        User user;
        try {
            user = userRepository.findByUuid(UUID.fromString(userUuid))
                    .orElseThrow(() -> new UnauthorizedException("Reset token invalid or expired", "TM_038"));
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Reset token invalid or expired", "TM_038");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // One-time use: consume the token so it can't be replayed.
        redisTemplate.delete(key);

        // Revoke all sessions/tokens — a reset should sign the user out everywhere.
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Password reset completed for user '{}'", user.getUsername());
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request, User currentUser) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), currentUser.getPasswordHash())) {
            throw new UnauthorizedException("Current password incorrect", "TM_042");
        }

        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);

        // Revoke all tokens
        refreshTokenRepository.revokeAllUserTokens(currentUser);
    }

    @Override
    @Transactional
    public void requestAccountDeletion(User currentUser) {
        // Re-load to operate on a managed entity (the principal may be detached).
        User user = userRepository.findById(currentUser.getId()).orElse(currentUser);
        if (user.isGuest()) {
            throw new ForbiddenException("Guest accounts can't be deleted; just close the tab.", "TM_029");
        }
        if (user.isDeleted()) {
            return; // already pending deletion — idempotent
        }

        user.setDeleted(true);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);

        // Sign the user out everywhere — no device should keep a working session.
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Account '{}' scheduled for deletion (recoverable for {} days)",
                user.getUsername(), accountDeletionWindowDays);
    }

    @Override
    @Transactional
    public int purgeExpiredDeletedAccounts() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(accountDeletionWindowDays));
        List<User> due = userRepository.findAccountsDueForPurge(cutoff);
        int purged = 0;
        for (User user : due) {
            try {
                refreshTokenRepository.revokeAllUserTokens(user);
                sessionRepository.deleteByUser(user);
                anonymizeAccount(user);
                userRepository.save(user);
                purged++;
                log.info("Permanently anonymized account id={} after deletion window elapsed", user.getId());
            } catch (Exception e) {
                log.error("Failed to purge account id={}: {}", user.getId(), e.getMessage());
            }
        }
        return purged;
    }

    /**
     * Irreversible permanent deletion: scrub all PII and destroy credentials so the
     * account can never be recovered or re-identified. The row is retained (not
     * hard-deleted) to preserve referential integrity of messages/posts authored by
     * the account, which are de-identified by this scrub. isDeleted stays true.
     */
    private void anonymizeAccount(User user) {
        user.setUsername("deleted_" + user.getUuid());
        user.setEmail(null);
        user.setPasswordHash(null);
        user.setName("Deleted User");
        user.setProfileImage(null);
        user.setMobileNumber(null);
        user.setBio(null);
        user.setOccupation(null);
        user.setEducation(null);
        user.setCountry(null);
        user.setCity(null);
        user.setAge(null);
        user.setGender(null);
        if (user.getInterests() != null) {
            user.getInterests().clear();
        }
        // Purge complete: clear the timer so it's no longer picked up by the reaper.
        user.setDeletionRequestedAt(null);
    }

    /** 256-bit URL-safe random token for password resets. */
    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private LoginResponse generateLoginResponse(User user, String userAgent, String ip) {
        // Single-device policy: invalidate any existing refresh tokens so a new
        // login signs the user out everywhere else. The previously-logged-in device
        // will get a 401 on its next refresh and be sent to the login page.
        refreshTokenRepository.revokeAllUserTokens(user);

        // ...and drop the superseded device's push subscriptions, so it stops receiving
        // push notifications immediately (it's no longer a valid session). The device
        // that just logged in re-registers its own subscription via NotificationSetup.
        try {
            webPushService.removeAllSubscriptionsForUser(user.getId());
        } catch (Exception e) {
            log.warn("Failed to clear push subscriptions on login for user {}", user.getId(), e);
        }

        // Generate Token pair
        String accessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest());
        String refreshTokenStr = UUID.randomUUID().toString();

        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        refreshTokenRepository.save(refreshToken);

        // Save session
        Session session = Session.builder()
                .user(user)
                .userAgent(userAgent)
                .ipAddress(ip)
                .isCurrent(true)
                .build();
        sessionRepository.save(session);

        AuthUserResponse authUser = userMapper.toAuthUserResponse(user);

        JwtTokensResponse jwtTokens = JwtTokensResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenStr)
                .expiresIn(accessTokenExpirationMs / 1000)
                .build();

        return LoginResponse.builder()
                .user(authUser)
                .tokens(jwtTokens)
                .build();
    }

    private Role getOrCreateRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).build()));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthUserResponse getCurrentUser(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        return userMapper.toAuthUserResponse(user);
    }

    @Override
    @Transactional
    public AuthUserResponse updateProfile(UpdateProfileRequest request, User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getProfileImage() != null) {
            user.setProfileImage(request.getProfileImage());
        }
        if (request.getCountry() != null) {
            user.setCountry(request.getCountry());
        }
        if (request.getCity() != null) {
            user.setCity(request.getCity());
        }
        if (request.getMobileNumber() != null) {
            user.setMobileNumber(request.getMobileNumber());
        }
        if (request.getPhone() != null) {
            user.setMobileNumber(request.getPhone());
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getOccupation() != null) {
            user.setOccupation(request.getOccupation());
        }
        if (request.getEducation() != null) {
            user.setEducation(request.getEducation());
        }
        if (request.getInterests() != null) {
            user.getInterests().clear();
            user.getInterests().addAll(request.getInterests());
        }

        user = userRepository.save(user);
        log.info("User profile updated successfully for: {}", user.getUsername());
        return userMapper.toAuthUserResponse(user);
    }
}
