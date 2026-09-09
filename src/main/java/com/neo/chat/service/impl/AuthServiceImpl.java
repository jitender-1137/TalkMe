package com.neo.chat.service.impl;

import com.neo.chat.cache.FeatureAccessCache;
import com.neo.chat.domain.RefreshToken;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.Session;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.OAuthUserInfo;
import com.neo.chat.dto.request.ChangePasswordRequest;
import com.neo.chat.dto.request.ForgotPasswordRequest;
import com.neo.chat.dto.request.GuestLoginRequest;
import com.neo.chat.dto.request.LoginRequest;
import com.neo.chat.dto.request.ResetPasswordRequest;
import com.neo.chat.dto.request.SignupRequest;
import com.neo.chat.dto.request.UpdateProfileRequest;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.CountryDetectionResult;
import com.neo.chat.dto.response.JwtTokensResponse;
import com.neo.chat.dto.response.LoginResponse;
import com.neo.chat.dto.response.SessionResponse;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.exception.UnauthorizedException;
import com.neo.chat.mapper.SessionMapper;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.repository.RefreshTokenRepository;
import com.neo.chat.repository.RoleRepository;
import com.neo.chat.repository.SessionRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.security.JwtTokenProvider;
import com.neo.chat.service.AuthService;
import com.neo.chat.service.CountryDetectionService;
import com.neo.chat.service.EmailService;
import com.neo.chat.service.FeatureAccessService;
import com.neo.chat.service.LoginAttemptService;
import com.neo.chat.service.PwnedPasswordService;
import com.neo.chat.service.ReputationRecorder;
import com.neo.chat.service.WebPushService;
import com.neo.chat.util.ClientRequestInfo;
import com.neo.chat.util.LogSanitizer;
import com.neo.chat.util.ProfileCompletion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authentication and account-lifecycle implementation: password / guest / Google-OAuth login,
 * signup, JWT access + rotating refresh tokens (single-device policy), session listing/revocation,
 * email verification, password reset, profile updates, and soft-delete with scheduled purge.
 *
 * <p>Cross-cutting behaviors: brute-force lockout via {@link LoginAttemptService}; best-effort
 * IP geolocation for country backfill, session location and sign-in alert emails; breached-password
 * rejection (HIBP); one-time reset/verification tokens stored in Redis only as SHA-256
 * hashes with TTLs and per-recipient send cooldowns; and feature-access cache eviction whenever an
 * action can change entitlement.
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final CountryDetectionService countryDetectionService;
    private final LoginAttemptService loginAttemptService;
    private final StringRedisTemplate redisTemplate;
    private final PwnedPasswordService pwnedPasswordService;
    private final EmailService emailService;
    private final WebPushService webPushService;
    private final ContentModerationService moderationService;
    private final UserSettingRepository userSettingRepository;
    private final FeatureAccessService featureAccessService;
    private final FeatureAccessCache featureAccessCache;
    private final ReputationRecorder reputationRecorder;

    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final long guestRefreshTokenExpirationMs;
    private final long passwordResetTtlMinutes;
    private final long emailVerificationTtlMinutes;
    private final long accountDeletionWindowDays;
    private final String frontendBaseUrl;

    public AuthServiceImpl(UserRepository userRepository,
                           RoleRepository roleRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           SessionRepository sessionRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider,
                           UserMapper userMapper,
                           SessionMapper sessionMapper,
                           CountryDetectionService countryDetectionService,
                           LoginAttemptService loginAttemptService,
                           StringRedisTemplate redisTemplate,
                           PwnedPasswordService pwnedPasswordService,
                           EmailService emailService,
                           WebPushService webPushService,
                           ContentModerationService moderationService,
                           UserSettingRepository userSettingRepository,
                           FeatureAccessService featureAccessService,
                           FeatureAccessCache featureAccessCache,
                           ReputationRecorder reputationRecorder,
                           @Value("${security.jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
                           @Value("${security.jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
                           @Value("${security.jwt.guest-refresh-token-expiration-ms}") long guestRefreshTokenExpirationMs,
                           @Value("${app.auth.password-reset-token-ttl-minutes:30}") long passwordResetTtlMinutes,
                           @Value("${app.auth.email-verification-token-ttl-minutes:1440}") long emailVerificationTtlMinutes,
                           @Value("${app.auth.account-deletion-window-days:30}") long accountDeletionWindowDays,
                           @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.countryDetectionService = countryDetectionService;
        this.loginAttemptService = loginAttemptService;
        this.redisTemplate = redisTemplate;
        this.pwnedPasswordService = pwnedPasswordService;
        this.emailService = emailService;
        this.webPushService = webPushService;
        this.moderationService = moderationService;
        this.userSettingRepository = userSettingRepository;
        this.featureAccessService = featureAccessService;
        this.featureAccessCache = featureAccessCache;
        this.reputationRecorder = reputationRecorder;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.guestRefreshTokenExpirationMs = guestRefreshTokenExpirationMs;
        this.passwordResetTtlMinutes = passwordResetTtlMinutes;
        this.emailVerificationTtlMinutes = emailVerificationTtlMinutes;
        this.accountDeletionWindowDays = accountDeletionWindowDays;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * Redis key prefix for one-time password-reset tokens (value = user UUID).
     */
    private static final String PWRESET_KEY_PREFIX = "pwreset:token:";
    /**
     * Redis key prefix for one-time email-verification tokens (value = user UUID).
     */
    private static final String EMAILVERIFY_KEY_PREFIX = "emailverify:token:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * Minimum seconds between transactional emails to the same recipient.
     */
    private static final int MAIL_COOLDOWN_SECONDS = 60;

    /**
     * Authenticates a password login by case-insensitive username or email. Enforces the
     * brute-force lockout, rejects guest/banned accounts, verifies the password, recovers a
     * soft-deleted account within its recovery window (else 401), backfills country from the
     * request IP when unset, fires a best-effort sign-in alert, and issues a token pair +
     * session (single-device: prior tokens are revoked). Transactional.
     *
     * @param request     the login credentials (email/username + password)
     * @param client      the caller's request context (User-Agent, client IP, proxy headers)
     * @return the login response with user + tokens
     * @throws com.neo.chat.exception.UnauthorizedException (TM_024) unknown user, bad password,
     *                                                         or a soft-deleted account past its recovery window
     * @throws com.neo.chat.exception.ForbiddenException    (TM_029) guest account using this flow,
     *                                                         or (TM_030) a banned account
     */
    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, ClientRequestInfo client) {
        String userAgent = client != null ? client.userAgent() : null;
        String ip = client != null ? client.clientIp() : null;
        // Email / username are case-insensitive; trim + normalize so login works
        // regardless of the case the user typed (matches how signup stores email).
        String identifier = request.getEmail() == null ? "" : request.getEmail().trim();

        // Brute-force guard: reject if this account/IP is locked out.
        loginAttemptService.assertNotBlocked(identifier, ip);

        User user = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .orElse(null);

        if (user == null) {
            loginAttemptService.recordFailure(identifier, ip);
            throw new UnauthorizedException("Invalid username or password", "TM_024");
        }

        if (user.isGuest()) {
            throw new ForbiddenException("Guest accounts must use Guest Login flow", "TM_029");
        }

        if (user.isBanned()) {
            loginAttemptService.recordFailure(identifier, ip);
            throw new ForbiddenException("This account has been suspended.", "TM_030");
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
                log.info("Account '{}' restored on login (was pending deletion)", user.getUuid());
            } else {
                throw new UnauthorizedException("This account has been deleted.", "TM_024");
            }
        }

        // Resolve the client's country + the closest location (city/area) from the request
        // IP once, and reuse it for: (a) one-time country backfill, (b) the session +
        // user activity-location record, and (c) the new-sign-in alert email. Best-effort
        // — a detection failure must never block login.
        CountryDetectionResult detection;
        try {
            detection = countryDetectionService.detectCountry(client);
        } catch (Exception e) {
            log.warn("Location detection on login failed for {}: {}", user.getUuid(), e.getMessage());
            detection = null;
        }

        // Backfill country ONLY when the user has none set; an existing country is
        // never overwritten.
        if (detection != null && (user.getCountry() == null || user.getCountry().isBlank())) {
            String detected = detection.getCountry();
            if (detected != null && !detected.isBlank() && !"Unknown".equalsIgnoreCase(detected)) {
                user.setCountry(detected);
                userRepository.save(user);
                log.info("Backfilled country '{}' for {} on login (source: {}, IP: {})",
                        detected, user.getUuid(), detection.getSource(), detection.getClientIp());
            }
        }

        // Fire a new-sign-in security alert with full device + location (best-effort,
        // async email, user-controllable).
        maybeSendLoginAlert(user, userAgent, detection);

        return generateLoginResponse(user, userAgent, detection);
    }

    /**
     * Registers a new password account: canonicalizes the email and enforces case-insensitive
     * uniqueness, rejects breached passwords, moderates the display name, detects country from
     * the request IP, resolves optional referral attribution, persists the user, sends the
     * verification email, and returns a login response. Transactional.
     *
     * @param request     the signup fields
     * @param client      the caller's request context (User-Agent, client IP, proxy headers)
     * @return the login response with user + tokens
     * @throws com.neo.chat.exception.ConflictException          (TM_047) if the email already exists
     * @throws com.neo.chat.exception.BadRequestException        (TM_496) if the password is breached
     * @throws com.neo.chat.exception.ContentModerationException if the display name is explicit
     */
    @Override
    @Transactional
    public LoginResponse signup(SignupRequest request, ClientRequestInfo client) {
        String userAgent = client != null ? client.userAgent() : null;
        // Canonicalize the email to lower-case (trimmed) so it's stored one way and
        // login matches regardless of the case the user types. The uniqueness check
        // is case-insensitive so "John@x.com" and "john@x.com" can't both register.
        String email = request.getEmail() == null ? null : request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("TM_047");
        }

        // Username uniqueness is CASE-INSENSITIVE (every lookup is findByUsernameIgnoreCase). The
        // DB constraint is case-sensitive, so without this check "alice" and "Alice" could both
        // register — and then findByUsernameIgnoreCase returns two rows and throws on every login
        // for either account (targeted lockout). Reject the collision up-front.
        String requestedUsername = request.getUsername() == null ? "" : request.getUsername().trim();
        if (userRepository.existsByUsernameIgnoreCase(requestedUsername)) {
            throw new ConflictException("This username is already taken.", "TM_048");
        }

        // Reject passwords known to appear in public breaches (HIBP k-anonymity).
        if (pwnedPasswordService.isBreached(request.getPassword())) {
            throw new BadRequestException(
                    "This password has appeared in a known data breach. Please choose a different one.", "TM_496");
        }

        // The display name is publicly visible — reject a non-clean one at signup.
        if (moderationService.moderateText(request.getName()).explicit()) {
            throw new ContentModerationException(
                    "Your display name contains content that violates our community guidelines.");
        }

        // Generate username from email prefix
        String username = request.getUsername();

        Role userRole = getOrCreateRole("ROLE_USER");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(client);

        // Referral attribution (who invited this user) — resolved BEFORE the insert so it's a single
        // save. Best-effort with no reward payout: any problem yields null and the signup proceeds
        // (a second write here could mark the tx rollback-only and defeat "never block signup").
        User referrer = resolveReferrer(request.getReferredByUsername(), username);

        User user = User.builder()
                .name(request.getName())
                .email(email)
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isGuest(false)
                .isVerified(false)
                .roles(Set.of(userRole))
                .age(request.getAge())
                .gender(request.getGender())
                .country(detectionResult.getCountry())
                .referredBy(referrer)
                .build();

        user = userRepository.save(user);
        log.info("User registered successfully: {}. Country detected: {} (Source: {}, IP: {}){}",
                user.getUuid(), detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp(),
                referrer != null ? " | invited by " + referrer.getUuid() : "");

        // Send the verification email first. The welcome email is sent later, only once
        // the address is actually confirmed via verifyEmail().
        sendVerificationEmail(user);

        return generateLoginResponse(user, userAgent, detectionResult);
    }

    /**
     * Resolve who invited a new signup, or null. Best-effort — a blank/unknown/self/guest/banned/
     * deleted referrer yields null (never throws, never blocks signup). No reward payout. Only a
     * read here, so it can't mark the surrounding signup transaction rollback-only.
     */
    private User resolveReferrer(String referrerUsername, String newUsername) {
        if (referrerUsername == null || referrerUsername.isBlank()) return null;
        try {
            String uname = referrerUsername.trim().replaceFirst("^@+", "");
            if (uname.isEmpty() || uname.equalsIgnoreCase(newUsername)) return null;
            return userRepository.findByUsernameIgnoreCase(uname)
                    .filter(ref -> !ref.isGuest() && !ref.isBanned() && !ref.isDeleted())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Referral lookup skipped: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Creates an anonymous guest account (random {@code guest_*} username, ROLE_GUEST, unverified)
     * with detected country, and returns a login response (guest refresh-token TTL). Transactional.
     *
     * @param request     the guest details (display name, age, gender)
     * @param client      the caller's request context (User-Agent, client IP, proxy headers)
     * @return the login response with the guest user + tokens
     */
    @Override
    @Transactional
    public LoginResponse loginAsGuest(GuestLoginRequest request, ClientRequestInfo client) {
        String userAgent = client != null ? client.userAgent() : null;
        // ABUSE GUARD: cap guest-account creation per client IP per day. Each guest login inserts a
        // permanent users/session/refresh row, so an unbounded loop bloats the DB and inflates
        // stats. Fail-OPEN if Redis is unavailable.
        if (!guestCreationAllowed(client != null ? client.clientIp() : "unknown")) {
            throw new TooManyRequestsException(
                    "Too many guest sessions from this network today. Please try again later or sign up.", "TM_007");
        }
        String username = "guest_" + UUID.randomUUID().toString().substring(0, 8);
        Role guestRole = getOrCreateRole("ROLE_GUEST");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(client);

        User guest = User.builder()
                .name(request.getName())
                .username(username)
                .age(request.getAge())
                .gender(request.getGender())
                .isGuest(true)
                .isVerified(false) // guests are NOT verified users
                .roles(Set.of(guestRole))
                .country(detectionResult.getCountry())
                .build();

        guest = userRepository.save(guest);
        log.info("Guest user logged in: {}. Country detected: {} (Source: {}, IP: {})",
                guest.getUuid(), detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp());

        return generateLoginResponse(guest, userAgent, detectionResult);
    }

    /**
     * Logs in (or provisions) a Google-OAuth user. Matches by provider id then canonical email,
     * creating a verified account on first use (handling the concurrent-create race via the
     * unique constraint) or linking the Google identity and backfilling missing fields on an
     * existing account (also recovering a soft-deleted one). Sends a welcome email for brand-new
     * users, otherwise a best-effort sign-in alert. Transactional.
     *
     * @param info        the OAuth profile (provider id, email, name, picture, verified, age, gender)
     * @param client      the caller's request context (User-Agent, client IP, proxy headers)
     * @return the login response with user + tokens
     * @throws org.springframework.dao.DataIntegrityViolationException if a creation race cannot be
     *                                                                 resolved to an existing row
     */
    @Override
    @Transactional
    public LoginResponse oauthLogin(OAuthUserInfo info, ClientRequestInfo client) {
        String userAgent = client != null ? client.userAgent() : null;
        // Geolocate from the callback request IP — the OAuth callback is a top-level
        // browser navigation so the client IP is the real user's. Google's profile has
        // no reliable country, so we reuse the SAME detector as password/guest signup
        // (IP → proxy headers → server public IP fallback; see CountryDetectionService).
        CountryDetectionResult detection = countryDetectionService.detectCountry(client);

        // Canonical lower-case email (matches password-signup storage), so a Google
        // login links to the pre-existing password account regardless of case.
        String oauthEmail = (info.getEmail() == null || info.getEmail().isBlank())
                ? null : info.getEmail().trim().toLowerCase();

        // 1. Match an existing account: by provider id first, then by email (links the
        //    Google identity onto a pre-existing password account with the same email).
        User user = null;
        boolean newlyProvisioned = false;
        if (info.getProviderId() != null && !info.getProviderId().isBlank()) {
            user = userRepository.findByGoogleId(info.getProviderId()).orElse(null);
        }
        // SECURITY (account pre-hijacking, CWE-1390): only fall back to email-matching when GOOGLE
        // asserts the email is verified — an unverified IdP email must never be trusted to claim a
        // local account.
        if (user == null && oauthEmail != null && info.isEmailVerified()) {
            User byEmail = userRepository.findByEmailIgnoreCase(oauthEmail).orElse(null);
            if (byEmail != null) {
                if (byEmail.isVerified() || byEmail.getGoogleId() != null) {
                    // The local owner already proved control of this address (verified their email
                    // or previously linked Google) → safe to link the Google identity.
                    user = byEmail;
                } else {
                    // An UNVERIFIED local shell owns this address. It may be a pre-registration made
                    // by an attacker who never verified it, so we must not silently hand the Google
                    // user an account whose password someone else set. Reclaim the shell for the
                    // verified Google owner: clear the (attacker-chosen) password and revoke any
                    // outstanding tokens/sessions so the pre-registrant loses all access.
                    log.warn("OAuth: verified Google email {} claimed an UNVERIFIED local account {} — "
                            + "resetting its password and revoking tokens (possible pre-registration).",
                            LogSanitizer.mask(info.getEmail()), byEmail.getUuid());
                    byEmail.setPasswordHash(null);
                    byEmail.setVerified(true);
                    userRepository.save(byEmail);
                    refreshTokenRepository.revokeAllUserTokens(byEmail);
                    sessionRepository.deleteByUser(byEmail);
                    user = byEmail;
                }
            }
        }

        if (user == null) {
            // 2. First-time Google user → create a full, verified account and persist
            //    the googleId, so every subsequent login resolves to this same row.
            Role userRole = getOrCreateRole("ROLE_USER");
            String name = (info.getName() != null && !info.getName().isBlank()) ? info.getName() : "User";
            User newUser = User.builder()
                    .name(name)
                    .email(oauthEmail)
                    .username(generateUniqueUsername(info.getEmail(), name))
                    .googleId(info.getProviderId())
                    .isGuest(false)
                    .isVerified(info.isEmailVerified())
                    .roles(Set.of(userRole))
                    .age(info.getAge())        // may be null (Google rarely shares it)
                    .gender(info.getGender())  // may be null
                    .profileImage(info.getPicture())
                    .country(detection.getCountry())
                    .build();
            try {
                user = userRepository.save(newUser);
                newlyProvisioned = true;
                log.info("New Google user provisioned: {} (email: {}, country: {}, source: {})",
                        user.getUuid(), LogSanitizer.mask(info.getEmail()), detection.getCountry(), detection.getSource());
            } catch (DataIntegrityViolationException e) {
                // Concurrent first-login for the same identity: the unique google_id/
                // email constraint rejected the duplicate. Reuse the row that won the
                // race so the same Google account always maps to one user id.
                user = userRepository.findByGoogleId(info.getProviderId())
                        .or(() -> oauthEmail != null
                                ? userRepository.findByEmailIgnoreCase(oauthEmail)
                                : Optional.empty())
                        .orElseThrow(() -> e);
                log.info("Reused existing Google user after create race: {}", user.getUuid());
            }
        } else {
            // 3. Existing account → link + backfill any fields we don't already have.
            boolean dirty = false;
            if (user.getGoogleId() == null && info.getProviderId() != null) {
                user.setGoogleId(info.getProviderId());
                dirty = true;
            }
            if ((user.getProfileImage() == null || user.getProfileImage().isBlank())
                    && info.getPicture() != null) {
                user.setProfileImage(info.getPicture());
                dirty = true;
            }
            if ((user.getAge() == null || user.getAge() == 0) && info.getAge() != null) {
                user.setAge(info.getAge());
                dirty = true;
            }
            if ((user.getGender() == null || user.getGender().isBlank()) && info.getGender() != null) {
                user.setGender(info.getGender());
                dirty = true;
            }
            if (!user.isVerified() && info.isEmailVerified()) {
                user.setVerified(true);
                dirty = true;
            }
            // Backfill country only when we don't already have one (never overwrite).
            if (user.getCountry() == null || user.getCountry().isBlank()) {
                String detected = detection.getCountry();
                if (detected != null && !detected.isBlank() && !"Unknown".equalsIgnoreCase(detected)) {
                    user.setCountry(detected);
                    dirty = true;
                }
            }
            // Google sign-in also recovers a soft-deleted account (same as password login).
            if (user.isDeleted()) {
                user.setDeleted(false);
                user.setDeletionRequestedAt(null);
                dirty = true;
            }
            if (dirty) {
                user = userRepository.save(user);
            }
        }

        if (newlyProvisioned) {
            // Google has already verified the email, so there's no verification step —
            // send the welcome email straight away (mirrors verifyEmail() for password signup).
            String openLink = frontendBaseUrl.replaceAll("/+$", "") + "/";
            emailService.sendWelcomeEmail(user.getEmail(), user.getName(), openLink);
        } else {
            // Returning sign-in (or linking Google to an existing account) → new-sign-in
            // alert, honoring the user's emailLoginAlerts preference.
            maybeSendLoginAlert(user, userAgent, detection);
        }

        return generateLoginResponse(user, userAgent, detection);
    }

    /**
     * Derive a unique username for a social-login account from the email local-part
     * (or the display name), stripped to safe characters and suffixed on collision.
     */
    private String generateUniqueUsername(String email, String name) {
        String base;
        if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else base = Objects.requireNonNullElse(name, "user");
        base = base.toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String candidate = base;
        // Case-insensitive to match every username lookup (findByUsernameIgnoreCase) and the
        // signup uniqueness check — a case-only collision would otherwise be allowed here.
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + "_" + Integer.toHexString(SECURE_RANDOM.nextInt(0x10000));
        }
        return candidate;
    }

    /** Grace window in which a just-rotated (revoked) refresh token is still honored by following
     *  its replacement chain, so concurrent refreshes from a fast reload / multiple tabs are not
     *  logged out. Outside this window a revoked token is treated as a real supersession. */
    private static final long REFRESH_REUSE_GRACE_SECONDS = 30L;

    /**
     * Follows a revoked token's {@code replacedByToken} chain to the current ACTIVE (non-revoked,
     * non-expired) token, taking a write lock on each step so it serializes with any concurrent
     * rotation. Returns {@code null} when the token was revoked longer ago than
     * {@link #REFRESH_REUSE_GRACE_SECONDS} (a genuine supersession, not a race) or the chain does
     * not lead to a live token.
     *
     * @param revoked the presented, already-revoked token
     * @return the current active replacement token, or {@code null} if none within the grace window
     */
    private RefreshToken resolveActiveReplacement(RefreshToken revoked) {
        Instant revokedAt = revoked.getUpdatedAt();
        if (revokedAt == null || revokedAt.isBefore(Instant.now().minusSeconds(REFRESH_REUSE_GRACE_SECONDS))) {
            return null; // outside the grace window → treat as a real logout
        }
        RefreshToken cur = revoked;
        for (int i = 0; i < 50; i++) { // depth guard against a broken/cyclic chain
            String next = cur.getReplacedByToken();
            if (next == null || next.isBlank()) return null;
            RefreshToken nextTok = refreshTokenRepository.findByTokenForUpdate(next).orElse(null);
            if (nextTok == null) return null;
            if (!nextTok.isRevoked() && !nextTok.isExpired()) return nextTok;
            cur = nextTok;
        }
        return null;
    }

    /**
     * Rotates a refresh token: validates it, revokes the old one and issues a new access +
     * refresh token pair, then updates the matching session's last-active timestamp. A revoked/
     * expired token (single-device supersession or an already-rotated token) is rejected; the
     * rotation is flushed immediately so a concurrent refresh of the same token loses the
     * optimistic-lock race with a clean 401 rather than a 500. Transactional.
     *
     * @param tokenStr  the presented refresh token
     * @param userAgent the caller's User-Agent (to match the session)
     * @param ip        the caller IP (to match the session)
     * @return the new access + refresh token pair
     * @throws com.neo.chat.exception.UnauthorizedException (TM_026) unknown, revoked, expired,
     *                                                         or concurrently-rotated token
     */
    @Override
    @Transactional
    public JwtTokensResponse refresh(String tokenStr, String userAgent, String ip) {
        // PESSIMISTIC lock: serialize concurrent refreshes of this token (fast reload / multiple
        // tabs sharing one cookie) so they don't collide on the optimistic version and log the user
        // out — see resolveActiveReplacement below.
        RefreshToken token = refreshTokenRepository.findByTokenForUpdate(tokenStr)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token", "TM_026"));

        User user = token.getUser();

        // A banned or soft-deleted account must not be able to mint fresh access tokens. Login
        // and the HTTP/WS auth gates already block them, but refresh only checked the token
        // state — so a banned user's client could refresh indefinitely and keep a live token.
        if (user.isBanned() || user.isDeleted()) {
            throw new UnauthorizedException("Account is not available. Please contact support.", "TM_026");
        }

        // Single-device policy: a revoked token means this device was superseded —
        // either the user signed in on another device (which revokes prior tokens),
        // or this exact token was already rotated. Either way it's no longer valid,
        // so reject with 401 and let this device clear its state + show the login
        // page. We deliberately DO NOT revoke all sessions here: that would also
        // log out the device that currently holds the valid token (and turned a
        // benign refresh race into a logout storm).
        // A genuinely expired token is a real logout — the client must re-authenticate.
        if (token.isExpired()) {
            throw new UnauthorizedException(
                    "Session expired. Please log in again.", "TM_026");
        }
        // A REVOKED token usually means a concurrent refresh (fast reload / another tab) already
        // rotated it a moment ago. Within a short grace window, follow the rotation chain to the
        // current ACTIVE token and re-issue a fresh access token bound to it, re-setting the cookie
        // to that active token so this lagging client catches up — but WITHOUT rotating again.
        // Rotating on every duplicate would extend the chain one link per request; under a rapid
        // reload burst (many requests still holding the original cookie) that blows past the
        // chain-depth guard and logs the user out. Not rotating lets all the duplicate in-flight
        // requests converge on the same active token. Outside the window it is a genuine
        // supersession (e.g. signed in on another device) → reject, which is a real logout.
        if (token.isRevoked()) {
            RefreshToken active = resolveActiveReplacement(token);
            if (active == null) {
                throw new UnauthorizedException(
                        "Session expired or signed in on another device. Please log in again.", "TM_026");
            }
            String graceAccessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest(),
                    user.getUuid() != null ? user.getUuid().toString() : null);
            touchSession(user, ip, userAgent);
            return JwtTokensResponse.builder()
                    .accessToken(graceAccessToken)
                    .refreshToken(active.getToken())
                    .expiresIn(accessTokenExpirationMs / 1000)
                    .user(toAuthUserResponse(user))
                    .build();
        }

        // Invalidate old token and replace
        token.setRevoked(true);

        // Generate new access token
        String newAccessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest(),
                user.getUuid() != null ? user.getUuid().toString() : null);

        // Generate rotated refresh token
        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;
        String newRefreshTokenStr = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .token(newRefreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        token.setReplacedByToken(newRefreshTokenStr);
        try {
            // Flush the rotation NOW so a concurrent refresh of the SAME token
            // (multiple tabs, or a burst of queued requests all firing after the
            // access token expired) is caught here as an optimistic-lock failure
            // instead of exploding at commit time with a 500. The request that loses
            // the race is a benign "already rotated" attempt → give it a clean 401
            // (TM_026), which the client handles by re-authenticating.
            refreshTokenRepository.saveAndFlush(token);
        } catch (OptimisticLockingFailureException e) {
            throw new UnauthorizedException(
                    "Session was just refreshed by another request. Please log in again.", "TM_026");
        }
        refreshTokenRepository.save(newRefreshToken);

        touchSession(user, ip, userAgent);

        return JwtTokensResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshTokenStr)
                .expiresIn(accessTokenExpirationMs / 1000)
                // Include the user so a page-reload session restore is ONE round trip
                // (see JwtTokensResponse.user). Same payload as GET /auth/me — built from
                // the managed user we already hold (no extra DB read).
                .user(toAuthUserResponse(user))
                .build();
    }

    /**
     * Bumps the last-active timestamp of the session matching this caller's IP + User-Agent.
     * A no-op when no session matches (e.g. a refresh from a device whose session row was pruned).
     *
     * @param user      the token owner
     * @param ip        the caller IP
     * @param userAgent the caller User-Agent
     */
    private void touchSession(User user, String ip, String userAgent) {
        List<Session> sessions = sessionRepository.findByUserAndIsDeletedFalse(user);
        for (Session session : sessions) {
            if (ip.equals(session.getIpAddress()) && userAgent.equals(session.getUserAgent())) {
                session.setLastActiveAt(Instant.now());
                sessionRepository.save(session);
            }
        }
    }

    /**
     * Revokes the given refresh token (no-op if unknown). Transactional.
     *
     * @param refreshTokenStr the refresh token to revoke
     */
    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        RefreshToken token = refreshTokenRepository.findByToken(refreshTokenStr).orElse(null);
        if (token != null) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            log.info("Logout successful for user: {}", token.getUser().getUuid());
        }
    }

    /**
     * Lists the user's active (non-deleted) sessions. Read-only.
     *
     * @param currentUser the user
     * @return the active session DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public List<SessionResponse> getSessions(User currentUser) {
        return sessionRepository.findByUserAndIsDeletedFalse(currentUser).stream()
                .map(sessionMapper::toSessionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Soft-deletes a single session, after checking it belongs to the caller. Transactional.
     *
     * @param sessionUuid the session UUID
     * @param currentUser the owning user
     * @throws com.neo.chat.exception.NotFoundException  (TM_053) if the session is missing
     * @throws com.neo.chat.exception.ForbiddenException (TM_103) if it belongs to another user
     */
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

    /**
     * Soft-deletes all the user's sessions except the current one. Transactional.
     *
     * @param currentUser the user
     */
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

    /**
     * Initiates password reset for a real, non-guest, active account: mints a single-use token
     * (only its SHA-256 hash + user UUID are stored in Redis with a TTL) and emails the reset
     * link. Anti-enumeration (silent return for missing/guest/deleted accounts) and per-recipient
     * cooldown (anti-bombing) are enforced. Not transactional (Redis + email side effects).
     *
     * @param request the forgot-password request carrying the email
     */
    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        // Never reveal whether the email exists (anti-enumeration): always return
        // success to the controller. Only real, non-guest, active accounts get a link.
        String email = request.getEmail() == null ? "" : request.getEmail().trim();
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.isGuest() || user.isDeleted()) {
            return;
        }

        // Per-recipient cooldown (anti-bombing). Silent return keeps anti-enumeration.
        if (!mailCooldownOk("pwreset", user.getEmail()) || !mailDailyCapOk("pwreset", user.getEmail())) {
            log.info("Password reset for user '{}' suppressed — cooldown active", user.getUuid());
            return;
        }

        // Cryptographically-strong, single-use token. Only its SHA-256 hash is stored
        // in Redis; the live token travels solely in the emailed link.
        String token = generateSecureToken();
        redisTemplate.opsForValue().set(
                PWRESET_KEY_PREFIX + hashToken(token),
                user.getUuid().toString(),
                Duration.ofMinutes(passwordResetTtlMinutes));

        String resetLink = frontendBaseUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetLink, passwordResetTtlMinutes);
        log.info("Password reset requested for user '{}'", user.getUuid());
    }

    /**
     * Completes a password reset: validates the token via its Redis-stored hash, rejects breached
     * passwords, sets the new password hash, consumes the token (one-time use), and revokes all
     * the user's sessions/tokens (sign-out everywhere). Transactional.
     *
     * @param request the reset request (token + new password)
     * @throws com.neo.chat.exception.UnauthorizedException (TM_038) missing/invalid/expired token
     * @throws com.neo.chat.exception.BadRequestException   (TM_496) if the new password is breached
     */
    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (request.getToken() == null || request.getToken().isBlank()) {
            throw new UnauthorizedException("Reset token invalid or expired", "TM_038");
        }
        String key = PWRESET_KEY_PREFIX + hashToken(request.getToken());
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

        if (pwnedPasswordService.isBreached(request.getPassword())) {
            throw new BadRequestException(
                    "This password has appeared in a known data breach. Please choose a different one.", "TM_496");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // One-time use: consume the token so it can't be replayed.
        redisTemplate.delete(key);

        // Revoke all sessions/tokens — a reset should sign the user out everywhere.
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Password reset completed for user '{}'", user.getUuid());
    }

    /**
     * Verifies an email-address token: validates it via its Redis-stored hash, consumes it
     * (one-time), and marks the user verified (idempotent when already verified). On first
     * verification it evicts the feature-access cache and sends the welcome email. Transactional.
     *
     * @param token the emailed verification token
     * @throws com.neo.chat.exception.UnauthorizedException (TM_403) missing/invalid/expired token
     */
    @Override
    @Transactional
    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Verification token invalid or expired", "TM_403");
        }
        String key = EMAILVERIFY_KEY_PREFIX + hashToken(token);
        String userUuid = redisTemplate.opsForValue().get(key);
        if (userUuid == null) {
            throw new UnauthorizedException("Verification token invalid or expired", "TM_403");
        }

        User user;
        try {
            user = userRepository.findByUuid(UUID.fromString(userUuid))
                    .orElseThrow(() -> new UnauthorizedException("Verification token invalid or expired", "TM_403"));
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Verification token invalid or expired", "TM_403");
        }

        // One-time use: consume the token immediately.
        redisTemplate.delete(key);

        // Idempotent: re-verifying an already-verified account is a no-op (no duplicate welcome).
        if (user.isVerified()) {
            log.info("Email already verified for user '{}'", user.getUuid());
            return;
        }

        user.setVerified(true);
        userRepository.save(user);
        // Verification unlocks verified-gated features — drop the stale entitlement cache.
        featureAccessCache.evict(user.getId());
        log.info("Email verified for user '{}'", user.getUuid());

        // Now — and only now — send the welcome email.
        String openLink = frontendBaseUrl.replaceAll("/+$", "") + "/";
        emailService.sendWelcomeEmail(user.getEmail(), user.getName(), openLink);
    }

    /**
     * Resends the verification email to an unverified, non-guest user (no-op for guests / missing
     * email). Read-only transaction (Redis + email side effects only).
     *
     * @param currentUser the user requesting a resend
     * @throws com.neo.chat.exception.BadRequestException (TM_404) if already verified
     */
    @Override
    @Transactional(readOnly = true)
    public void resendVerificationEmail(User currentUser) {
        if (currentUser == null || currentUser.isGuest() || currentUser.getEmail() == null) {
            return;
        }
        if (currentUser.isVerified()) {
            throw new BadRequestException("Your email is already verified.", "TM_404");
        }
        sendVerificationEmail(currentUser);
    }

    /**
     * Mints a one-time verification token, stores it in Redis, and emails the link.
     */
    private void sendVerificationEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        // Per-recipient cooldown so a signup + rapid "resend" can't email-bomb an address.
        if (!mailCooldownOk("verify", user.getEmail()) || !mailDailyCapOk("verify", user.getEmail())) {
            log.info("Verification email for user '{}' suppressed — cooldown active", user.getUuid());
            return;
        }
        String token = generateSecureToken();
        redisTemplate.opsForValue().set(
                EMAILVERIFY_KEY_PREFIX + hashToken(token),
                user.getUuid().toString(),
                Duration.ofMinutes(emailVerificationTtlMinutes));
        String verifyLink = frontendBaseUrl.replaceAll("/+$", "") + "/verify-email?token=" + token;
        emailService.sendVerificationEmail(user.getEmail(), user.getName(), verifyLink, emailVerificationTtlMinutes);
        log.info("Verification email sent for user '{}'", user.getUuid());
    }

    /**
     * Sends a new-sign-in alert if the user hasn't opted out. Best-effort, never throws.
     */
    private void maybeSendLoginAlert(User user, String userAgent, CountryDetectionResult detection) {
        try {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                return;
            }
            boolean alertsOn = userSettingRepository.findByUser(user)
                    .map(UserSetting::isEmailLoginAlerts)
                    .orElse(true);
            if (!alertsOn) {
                return;
            }
            // Show the time in the LOGIN LOCATION's own timezone (e.g. "August 7 at
            // 10:42 AM (IST)"), falling back to UTC when GeoIP gave no zone.
            ZoneId zone = detection != null ? detection.getZoneId() : ZoneOffset.UTC;
            String when = DateTimeFormatter
                    .ofPattern("MMMM d 'at' h:mm a (zzz)", Locale.ENGLISH)
                    .withZone(zone)
                    .format(Instant.now());
            String device = friendlyDevice(userAgent);
            String location = detection != null ? detection.getDisplayLocation() : null;
            String ip = detection != null ? detection.getClientIp() : null;
            String secureLink = frontendBaseUrl.replaceAll("/+$", "") + "/forgot-password";
            emailService.sendLoginAlertEmail(user.getEmail(), user.getName(), device, location, ip, when, secureLink);
        } catch (Exception e) {
            log.warn("Login-alert email skipped for '{}': {}", user.getUuid(), e.getMessage());
        }
    }

    /**
     * Best-effort friendly device label from a raw User-Agent string.
     */
    private static String friendlyDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String os = userAgent.contains("Windows") ? "Windows"
                : userAgent.contains("iPhone") ? "iPhone"
                  : userAgent.contains("iPad") ? "iPad"
                    : userAgent.contains("Android") ? "Android"
                      : (userAgent.contains("Mac OS") || userAgent.contains("Macintosh")) ? "Mac"
                        : userAgent.contains("Linux") ? "Linux" : null;
        String browser = userAgent.contains("Edg") ? "Edge"
                : userAgent.contains("OPR") || userAgent.contains("Opera") ? "Opera"
                  : userAgent.contains("Chrome") ? "Chrome"
                    : userAgent.contains("Firefox") ? "Firefox"
                      : userAgent.contains("Safari") ? "Safari" : null;
        if (os == null && browser == null) {
            return userAgent.length() > 60 ? userAgent.substring(0, 60) + "…" : userAgent;
        }
        if (os != null && browser != null) {
            return browser + " on " + os;
        }
        return os != null ? os : browser;
    }

    /**
     * Changes the signed-in user's password after confirming the current one and rejecting a
     * breached new password, then revokes all the user's tokens (sign-out everywhere).
     * Transactional.
     *
     * @param request     the change-password request (current + new password)
     * @param currentUser the signed-in user
     * @throws com.neo.chat.exception.UnauthorizedException (TM_042) if the current password
     *                                                         is incorrect
     * @throws com.neo.chat.exception.BadRequestException   (TM_496) if the new password is breached
     */
    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request, User currentUser) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), currentUser.getPasswordHash())) {
            throw new UnauthorizedException("Current password incorrect", "TM_042");
        }
        if (pwnedPasswordService.isBreached(request.getNewPassword())) {
            throw new BadRequestException(
                    "This password has appeared in a known data breach. Please choose a different one.", "TM_496");
        }

        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);

        // Revoke all tokens
        refreshTokenRepository.revokeAllUserTokens(currentUser);
    }

    /**
     * Schedules a soft account deletion (recoverable for the configured window): re-authenticates
     * with the password for accounts that have one (OAuth-only accounts are exempt), marks the
     * user deleted with a timestamp, and revokes all tokens. Idempotent when already pending.
     * Transactional.
     *
     * @param currentUser the account owner
     * @param password    the current password for re-authentication (required for local accounts)
     * @throws com.neo.chat.exception.ForbiddenException    (TM_029) if the account is a guest
     * @throws com.neo.chat.exception.UnauthorizedException (TM_497) if password confirmation
     *                                                         is missing or wrong for a local account
     */
    @Override
    @Transactional
    public void requestAccountDeletion(User currentUser, String password) {
        // Re-load to operate on a managed entity (the principal may be detached).
        User user = userRepository.findById(currentUser.getId()).orElse(currentUser);
        if (user.isGuest()) {
            throw new ForbiddenException("Guest accounts can't be deleted; just close the tab.", "TM_029");
        }
        if (user.isDeleted()) {
            return; // already pending deletion — idempotent
        }
        // Re-authenticate this destructive action. Accounts with a local password must
        // confirm it; OAuth-only accounts (no password hash) are exempt.
        String hash = user.getPasswordHash();
        if (hash != null && !hash.isBlank()) {
            if (password == null || !passwordEncoder.matches(password, hash)) {
                throw new UnauthorizedException(
                        "Password confirmation is required to delete your account", "TM_497");
            }
        }

        user.setDeleted(true);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);

        // Sign the user out everywhere — no device should keep a working session.
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Account '{}' scheduled for deletion (recoverable for {} days)",
                user.getUuid(), accountDeletionWindowDays);
    }

    /**
     * Scheduled purge: for each soft-deleted account past its recovery window, revokes tokens,
     * deletes sessions and anonymizes PII (per-account failures are logged and skipped, not fatal).
     * Transactional.
     *
     * @return the number of accounts anonymized
     */
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

    /**
     * 256-bit URL-safe random token for password resets.
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 hex of a token. Redis stores only this hash as the lookup key, never
     * the live token — so read access to Redis (see the prior Redis-hijack incident)
     * can't yield a usable reset/verification token. The plaintext token still
     * travels only in the emailed link.
     */
    private String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Per-recipient email cooldown (anti email-bombing). Returns true and reserves
     * the slot if a Send is allowed now; false if one was sent within the window.
     * Fail-open so a Redis blip never blocks a legitimate reset.
     */
    private boolean mailCooldownOk(String type, String email) {
        if (email == null || email.isBlank()) return true;
        try {
            String key = "mail:cooldown:" + type + ":" + hashToken(email.trim().toLowerCase());
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(MAIL_COOLDOWN_SECONDS));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            return true;
        }
    }

    /** Max guest accounts created per client IP per rolling 24h. */
    private static final int GUEST_CREATE_CAP_PER_IP = 20;

    /**
     * Per-IP daily cap on guest-account creation. Fail-OPEN if Redis is down.
     *
     * @param ip resolved client IP
     * @return true if still under the daily cap
     */
    private boolean guestCreationAllowed(String ip) {
        if (ip == null || ip.isBlank()) return true;
        try {
            String key = "guest:create:ip:" + ip;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(24));
            }
            return count == null || count <= GUEST_CREATE_CAP_PER_IP;
        } catch (Exception e) {
            return true;
        }
    }

    /** Max transactional emails of one type to a single recipient per rolling 24h. */
    private static final int MAIL_DAILY_CAP_PER_RECIPIENT = 5;

    /**
     * Per-recipient DAILY cap for a transactional-mail type (on top of the 60s cooldown). Stops an
     * attacker from mail-bombing a victim address (or burning the shared provider quota) by
     * repeatedly triggering forgot-password / resend-verification. Fail-OPEN if Redis is down.
     *
     * @param type  mail type ("pwreset" | "verify")
     * @param email recipient address
     * @return true if still under the daily cap
     */
    private boolean mailDailyCapOk(String type, String email) {
        if (email == null || email.isBlank()) return true;
        try {
            String key = "mail:daily:" + type + ":" + hashToken(email.trim().toLowerCase());
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(24));
            }
            return count == null || count <= MAIL_DAILY_CAP_PER_RECIPIENT;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Finalizes a successful authentication: enforces the single-device policy by revoking all
     * prior refresh tokens and clearing the superseded device's push subscriptions (best-effort),
     * issues a new access + refresh token pair, persists a session and the user's latest activity
     * location, and returns the user DTO (with a freshly-recomputed feature set) plus tokens.
     *
     * @param user      the authenticated user
     * @param userAgent the caller's User-Agent (recorded on the session)
     * @param detection the resolved location (nullable) for IP/location fields
     * @return the assembled login response
     */
    private LoginResponse generateLoginResponse(User user, String userAgent, CountryDetectionResult detection) {
        String ip = detection != null ? detection.getClientIp() : null;
        String location = detection != null ? detection.getDisplayLocation() : null;
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
        String accessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest(),
                user.getUuid() != null ? user.getUuid().toString() : null);
        String refreshTokenStr = UUID.randomUUID().toString();

        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        refreshTokenRepository.save(refreshToken);

        // Save session (with the resolved "closest location" when known).
        Session session = Session.builder()
                .user(user)
                .userAgent(userAgent)
                .ipAddress(ip)
                .location(location)
                .isCurrent(true)
                .build();
        sessionRepository.save(session);

        // Record the latest activity location on the user for the admin dashboard.
        // Best-effort: only update when we actually resolved something useful, so a
        // failed geo-lookup never wipes a previously-known location.
        if (ip != null && !ip.isBlank()) {
            user.setLastLoginIp(ip);
        }
        if (location != null && !location.isBlank()) {
            user.setLastLocation(location);
        }
        user.setLastLocationAt(Instant.now());
        userRepository.save(user);

        // Login may have just flipped verified/age (OAuth backfill) — recompute the
        // entitlement set fresh rather than serving a pre-login cached value.
        featureAccessCache.evict(user.getId());
        AuthUserResponse authUser = userMapper.toAuthUserResponse(user);
        authUser.setFeatures(featureAccessService.effectiveWireNames(user));
        authUser.setLockedFeatures(featureAccessService.verificationLockedWireNames(user));
        authUser.setVerificationRequired(featureAccessService.isVerificationRequired());

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

    /**
     * Returns the named role, creating it if it does not yet exist.
     *
     * @param roleName the role name (e.g. ROLE_USER, ROLE_GUEST)
     * @return the persisted role
     */
    private Role getOrCreateRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).build()));
    }

    /**
     * Re-loads the signed-in user and returns their profile DTO with the freshly-computed
     * effective feature set. Read-only.
     *
     * @param currentUser the signed-in user (principal)
     * @return the current-user DTO including features
     * @throws com.neo.chat.exception.NotFoundException (TM_024) if the user no longer exists
     */
    @Override
    @Transactional(readOnly = true)
    public AuthUserResponse getCurrentUser(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        return toAuthUserResponse(user);
    }

    /**
     * Maps an already-loaded {@link User} to the {@link AuthUserResponse} the client expects
     * (profile + effective/locked features + verification flag), WITHOUT re-reading from the
     * database. {@link #getCurrentUser} re-fetches first (its caller only holds a detached
     * principal); {@code refresh} already holds the managed user from the refresh token, so it
     * uses this directly to avoid a redundant query on the hot session-restore path.
     */
    private AuthUserResponse toAuthUserResponse(User user) {
        AuthUserResponse res = userMapper.toAuthUserResponse(user);
        res.setFeatures(featureAccessService.effectiveWireNames(user));
        res.setLockedFeatures(featureAccessService.verificationLockedWireNames(user));
        res.setVerificationRequired(featureAccessService.isVerificationRequired());
        return res;
    }

    /**
     * Empty/blank → null (so a cleared dropdown clears the column).
     */
    private static String blankOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Merges the supplied profile fields into the user (null = leave unchanged; blank on optional
     * "About me" dropdowns = clear), recomputes the cached profile-completion score, records a
     * reputation event on reaching 100%, evicts the feature-access cache, and returns the updated
     * DTO with a fresh feature set. Transactional.
     *
     * @param request     the partial profile update
     * @param currentUser the signed-in user
     * @return the updated current-user DTO including features
     * @throws com.neo.chat.exception.NotFoundException (TM_024) if the user no longer exists
     */
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
        // Optional "About me" dropdowns: null ⇒ unchanged, blank ⇒ clear.
        if (request.getBodyType() != null) user.setBodyType(blankOrNull(request.getBodyType()));
        if (request.getHairColor() != null) user.setHairColor(blankOrNull(request.getHairColor()));
        if (request.getEyeColor() != null) user.setEyeColor(blankOrNull(request.getEyeColor()));
        if (request.getRelationshipStatus() != null)
            user.setRelationshipStatus(blankOrNull(request.getRelationshipStatus()));
        if (request.getChildren() != null) user.setChildren(blankOrNull(request.getChildren()));
        if (request.getDrinking() != null) user.setDrinking(blankOrNull(request.getDrinking()));
        if (request.getSmoking() != null) user.setSmoking(blankOrNull(request.getSmoking()));
        if (request.getWorkout() != null) user.setWorkout(blankOrNull(request.getWorkout()));
        if (request.getZodiac() != null) user.setZodiac(blankOrNull(request.getZodiac()));
        if (request.getReligion() != null) user.setReligion(blankOrNull(request.getReligion()));
        if (request.getInterests() != null) {
            user.getInterests().clear();
            user.getInterests().addAll(request.getInterests());
        }
        // ── Late-Night Social attributes ──
        if (request.getMood() != null) {
            user.setMood(request.getMood());
            user.setMoodUpdatedAt(Instant.now());
        }
        if (request.getConversationEnergy() != null) {
            user.setConversationEnergy(request.getConversationEnergy());
        }
        if (request.getLanguages() != null) {
            user.getLanguages().clear();
            user.getLanguages().addAll(request.getLanguages());
        }
        if (request.getLookingFor() != null) {
            user.getLookingFor().clear();
            user.getLookingFor().addAll(request.getLookingFor());
        }
        if (request.getPersonality() != null) {
            user.getPersonality().clear();
            for (var e : request.getPersonality().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    user.getPersonality().put(e.getKey(), Math.clamp(e.getValue(), 0, 100));
                }
            }
        }
        if (request.getVoiceIntroUrl() != null) {
            user.setVoiceIntroUrl(request.getVoiceIntroUrl());
        }
        if (request.getVoiceIntroDurationMs() != null) {
            user.setVoiceIntroDurationMs(request.getVoiceIntroDurationMs());
        }
        // Recompute the cached completion score from the merged state.
        user.setProfileCompletion(ProfileCompletion.compute(user));

        user = userRepository.save(user);
        if (user.getProfileCompletion() >= 100) {
            reputationRecorder.record(user.getId(),
                    ReputationEventType.PROFILE_COMPLETED, String.valueOf(user.getId()));
        }
        log.info("User profile updated successfully for: {}", user.getUuid());
        // Age (and later verification) can change entitlement — evict so the returned
        // feature set is recomputed fresh rather than served from a stale cache.
        featureAccessCache.evict(user.getId());
        AuthUserResponse res = userMapper.toAuthUserResponse(user);
        res.setFeatures(featureAccessService.effectiveWireNames(user));
        res.setLockedFeatures(featureAccessService.verificationLockedWireNames(user));
        res.setVerificationRequired(featureAccessService.isVerificationRequired());
        return res;
    }
}
