package com.neo.chat.controller;

import com.neo.chat.util.ClientRequestInfo;
import com.neo.chat.dto.request.ChangePasswordRequest;
import com.neo.chat.dto.request.ForgotPasswordRequest;
import com.neo.chat.dto.request.GuestLoginRequest;
import com.neo.chat.dto.request.LoginRequest;
import com.neo.chat.dto.request.ResetPasswordRequest;
import com.neo.chat.dto.request.SignupRequest;
import com.neo.chat.dto.request.UpdateProfileRequest;
import com.neo.chat.dto.request.VerifyEmailRequest;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.JwtTokensResponse;
import com.neo.chat.dto.response.LoginResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SessionResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.UnauthorizedException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.AuthService;
import com.neo.chat.service.CaptchaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import com.neo.chat.util.ClientIp;
import com.neo.chat.security.JwtTokenProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Authentication and account/session management: signup, login (credentials + guest),
 * token refresh/logout, current-user profile, session revocation, and password/email flows.
 * Auth forms are CAPTCHA + honeypot gated; refresh/CSRF tokens are managed via HttpOnly cookies.
 */
@Slf4j
@RestController
@Tag(name = "Auth", description = "Authentication and account/session management: signup, login (credentials + guest), token refresh/logout, current-user profile, session...")
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final JwtTokenProvider tokenProvider;
    /** Whether auth cookies are flagged {@code Secure} ({@code app.cookie.secure}, default false). */
    private final boolean cookieSecure;
    /** {@code SameSite} attribute applied to auth cookies ({@code app.cookie.same-site}, default Lax). */
    private final String cookieSameSite;

    public AuthController(AuthService authService,
                          CaptchaService captchaService,
                          JwtTokenProvider tokenProvider,
                          @Value("${app.cookie.secure:false}") boolean cookieSecure,
                          @Value("${app.cookie.same-site:Lax}") String cookieSameSite) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.tokenProvider = tokenProvider;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    /**
     * Bean Validation for the dynamically-routed /auth/login body: it is parsed by hand (guest vs
     * credential flow share one endpoint), so {@code @Valid} never runs — validate explicitly.
     */
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** Cookie carrying the media-read token; scoped to the media serve endpoint only. */
    public static final String MEDIA_COOKIE = "media_token";
    /** Path scope of {@link #MEDIA_COOKIE} — never sent to any other endpoint. */
    public static final String MEDIA_COOKIE_PATH = "/api/v1/uploads/media";

    private <T> void validate(T dto) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(dto);
        if (!violations.isEmpty()) {
            String msg = violations.stream().map(ConstraintViolation::getMessage).sorted().findFirst().orElse("Invalid request");
            throw new BadRequestException(msg, "TM_002");
        }
    }

    /**
     * Bot/human gate for the auth forms: rejects filled honeypots and requires a
     * valid Cloudflare Turnstile token. Throws 400 if either check fails.
     */
    private void verifyHuman(String captchaToken, String honeypot, HttpServletRequest request) {
        if (honeypot != null && !honeypot.isBlank()) {
            log.warn("Honeypot triggered from IP {}", getClientIp(request));
            throw new BadRequestException("Request rejected", "TM_403");
        }
        if (!captchaService.verify(captchaToken, getClientIp(request))) {
            throw new BadRequestException("CAPTCHA verification failed. Please try again.", "TM_401");
        }
    }

    private void setAuthCookies(HttpServletResponse response, JwtTokensResponse tokens, boolean isGuest) {
        setAuthCookies(response, tokens.getRefreshToken(), isGuest);
        // 3. Media-read cookie (HttpOnly, Path-scoped to the media endpoint). Lets <img>/<video>
        //    loads authenticate without a header; rejected by the access-token path.
        String username = null;
        try {
            username = tokens.getAccessToken() != null ? tokenProvider.getUsernameFromToken(tokens.getAccessToken()) : null;
        } catch (Exception ignored) {
            // no media cookie if the access token cannot be read
        }
        if (username != null) {
            long maxAge = isGuest ? 7 * 24 * 60 * 60 : 30 * 24 * 60 * 60;
            String mediaToken = tokenProvider.generateMediaToken(username, maxAge * 1000L);
            if (mediaToken != null) {
                ResponseCookie mediaCookie = ResponseCookie.from(MEDIA_COOKIE, mediaToken)
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .path(MEDIA_COOKIE_PATH)
                        .maxAge(maxAge)
                        .sameSite(cookieSameSite)
                        .build();
                response.addHeader(HttpHeaders.SET_COOKIE, mediaCookie.toString());
            }
        }
    }

    private void setAuthCookies(HttpServletResponse response, String refreshToken, boolean isGuest) {
        long refreshMaxAge = isGuest ? 7 * 24 * 60 * 60 : 30 * 24 * 60 * 60; // seconds

        // 1. Refresh Token Cookie (HttpOnly)
        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(refreshMaxAge)
                .sameSite(cookieSameSite)
                .build();

        // 2. CSRF Token Cookie (non-HttpOnly so client JS can read it)
        String csrfToken = UUID.randomUUID().toString();
        ResponseCookie csrfCookie = ResponseCookie.from("csrf_token", csrfToken)
                .httpOnly(false)
                .secure(cookieSecure)
                .path("/")
                .maxAge(refreshMaxAge)
                .sameSite(cookieSameSite)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    private void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie mediaCookie = ResponseCookie.from(MEDIA_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path(MEDIA_COOKIE_PATH)
                .maxAge(0)
                .sameSite(cookieSameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, mediaCookie.toString());

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .sameSite(cookieSameSite)
                .build();

        ResponseCookie csrfCookie = ResponseCookie.from("csrf_token", "")
                .httpOnly(false)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .sameSite(cookieSameSite)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    private String getClientIp(HttpServletRequest request) {
        // Proxy-aware: reads the entry written by the trusted proxy, never the client-supplied
        // first hop (which would let an attacker reset the brute-force / CAPTCHA IP buckets).
        return ClientIp.resolve(request);
    }

    /**
     * Register a new user, then set refresh/CSRF auth cookies and return the login payload.
     *
     * @param request      the validated signup request (includes CAPTCHA token + honeypot)
     * @param userAgent    the caller's User-Agent header (recorded on the session)
     * @param httpRequest  the servlet request (used for client IP + human verification)
     * @param httpResponse the servlet response (auth cookies are written to it)
     * @return the {@link LoginResponse} with tokens and user profile
     * @throws com.neo.chat.exception.BadRequestException        if honeypot/CAPTCHA verification fails
     * @throws com.neo.chat.exception.ConflictException          if the username or email is taken
     * @throws com.neo.chat.exception.ContentModerationException if the chosen username fails moderation
     */
    @Operation(summary = "Register a new user, then set refresh/CSRF auth cookies and return the login payload")
    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<LoginResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        verifyHuman(request.getCaptchaToken(), request.getWebsite(), httpRequest);

        LoginResponse loginResponse = authService.signup(request, ClientRequestInfo.from(httpRequest, userAgent));

        setAuthCookies(httpResponse, loginResponse.getTokens(), false);

        return ResponseEntity.ok(SuccessResponseDto.success(loginResponse, "User Registered Successfully", "TM_001"));
    }

    /**
     * Unified login: routes to guest login when the body carries {@code isGuest:true}, otherwise
     * standard credential login; sets refresh/CSRF cookies on success.
     *
     * @param bodyRaw      the raw JSON body (parsed dynamically for guest vs. credential login)
     * @param userAgent    the caller's User-Agent header (recorded on the session)
     * @param httpRequest  the servlet request (used for client IP + human verification)
     * @param httpResponse the servlet response (auth cookies are written to it)
     * @return the {@link LoginResponse} with tokens and user profile
     * @throws com.neo.chat.exception.BadRequestException   if honeypot/CAPTCHA verification fails
     * @throws com.neo.chat.exception.UnauthorizedException if the credentials are invalid or the
     *                                                         account is deleted
     * @throws com.neo.chat.exception.ForbiddenException    if the account is suspended or a
     *                                                         non-guest account uses the guest flow (and vice versa)
     */
    @Operation(summary = "Unified login: routes to guest login when the body carries isGuest:true, otherwise standard credential login; sets refresh/CSRF cookies...")
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<LoginResponse>> login(
            @RequestBody String bodyRaw, // Allows parsing dynamic requests for guest mode
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ip = getClientIp(httpRequest);
        ObjectMapper mapper = new ObjectMapper();
        Object parsed = parseBody(bodyRaw);

        // Bot/human gate (CAPTCHA + honeypot) before any auth processing.
        if (parsed instanceof Map<?, ?> body) {
            Object token = body.get("captchaToken");
            Object honeypot = body.get("website");
            verifyHuman(token != null ? token.toString() : null,
                    honeypot != null ? honeypot.toString() : null, httpRequest);
        }

        // Unified route: check if body contains isGuest flag
        if (bodyRaw.contains("\"isGuest\":true") || bodyRaw.contains("\"isGuest\": true")) {
            // Guest login flow
            GuestLoginRequest request = mapper.convertValue(parsed, GuestLoginRequest.class);
            validate(request); // @NotBlank name, @ValidAge, @ValidGender — previously never enforced
            LoginResponse response = authService.loginAsGuest(request, ClientRequestInfo.from(httpRequest, userAgent));
            setAuthCookies(httpResponse, response.getTokens(), true);
            return ResponseEntity.ok(SuccessResponseDto.success(response, "Login Successful", "TM_002"));
        } else {
            // Standard credentials login
            LoginRequest request = mapper.convertValue(parsed, LoginRequest.class);
            validate(request); // @NotBlank identifier/password — avoids a null-password 400 with an internal message
            LoginResponse response = authService.login(request, ClientRequestInfo.from(httpRequest, userAgent, ip));
            setAuthCookies(httpResponse, response.getTokens(), false);
            return ResponseEntity.ok(SuccessResponseDto.success(response, "Login Successful", "TM_002"));
        }
    }

    private Object parseBody(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid login JSON structure");
        }
    }

    /**
     * Rotate the access/refresh token pair using the refresh-token cookie; re-issues both cookies.
     *
     * @param refreshToken the refresh token read from the {@code refreshToken} cookie
     * @param userAgent    the caller's User-Agent header (validated against the session)
     * @param httpRequest  the servlet request (used for client IP)
     * @param httpResponse the servlet response (rotated auth cookies are written to it)
     * @return the fresh {@link JwtTokensResponse}
     * @throws com.neo.chat.exception.UnauthorizedException if the cookie is missing, or the
     *                                                         refresh token is invalid/expired
     */
    @Operation(summary = "Rotate the access/refresh token pair using the refresh-token cookie; re-issues both cookies")
    @PostMapping("/refresh")
    public ResponseEntity<ResponseDto<JwtTokensResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Refresh token is missing", "TM_026");
        }

        String ip = getClientIp(httpRequest);
        JwtTokensResponse tokensResponse = authService.refresh(refreshToken, userAgent, ip);

        // Rotate cookies with the new refresh token
        setAuthCookies(httpResponse, tokensResponse, false);

        return ResponseEntity.ok(SuccessResponseDto.success(tokensResponse, "Token Refreshed Successfully", "TM_023"));
    }

    /**
     * Invalidate the current refresh token (if present) and clear the auth cookies.
     *
     * @param refreshToken the refresh token from the {@code refreshToken} cookie (maybe null/blank)
     * @param httpResponse the servlet response (auth cookies are cleared on it)
     * @return an empty success envelope confirming logout
     */
    @Operation(summary = "Invalidate the current refresh token (if present) and clear the auth cookies")
    @PostMapping("/logout")
    public ResponseEntity<ResponseDto<Void>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse httpResponse) {

        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }

        clearAuthCookies(httpResponse);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Logout Successful", "TM_003"));
    }

    /**
     * Return the authenticated user's own profile.
     *
     * @param userDetails the authenticated principal
     * @return the {@link AuthUserResponse} for the current user
     */
    @Operation(summary = "Return the authenticated user's own profile")
    @GetMapping("/me")
    public ResponseEntity<ResponseDto<AuthUserResponse>> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        AuthUserResponse response = authService.getCurrentUser(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Update the authenticated user's editable profile fields.
     *
     * @param request     the validated profile-update request
     * @param userDetails the authenticated principal
     * @return the updated {@link AuthUserResponse}
     */
    @Operation(summary = "Update the authenticated user's editable profile fields")
    @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<AuthUserResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AuthUserResponse response = authService.updateProfile(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Profile updated successfully", "TM_060"));
    }

    /**
     * List the authenticated user's active login sessions.
     *
     * @param userDetails the authenticated principal
     * @return the list of {@link SessionResponse} for the user
     */
    @Operation(summary = "List the authenticated user's active login sessions")
    @GetMapping("/sessions")
    public ResponseEntity<ResponseDto<List<SessionResponse>>> getSessions(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<SessionResponse> response = authService.getSessions(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Revoke (terminate) one of the authenticated user's sessions.
     *
     * @param sessionUuid the UUID of the session to revoke
     * @param userDetails the authenticated principal (must own the session)
     * @return an empty success envelope confirming termination
     * @throws com.neo.chat.exception.ForbiddenException if the session belongs to another user
     */
    @Operation(summary = "Revoke (terminate) one of the authenticated user's sessions")
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<ResponseDto<Void>> revokeSession(
            @PathVariable("id") String sessionUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.revokeSession(sessionUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Session terminated successfully", "TM_051"));
    }

    /**
     * Revoke all the authenticated user's other sessions.
     *
     * @param userDetails the authenticated principal
     * @return an empty success envelope confirming the other sessions were revoked
     */
    @Operation(summary = "Revoke all the authenticated user's other sessions")
    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<ResponseDto<Void>> revokeAllSessions(@AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.revokeAllSessions(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All other sessions revoked successfully", "TM_052"));
    }

    /**
     * Begin the password-reset flow by emailing a reset link (responds success regardless of
     * whether the address exists, to avoid account enumeration).
     *
     * @param request the validated forgot-password request (email)
     * @return an empty success envelope
     */
    @Operation(summary = "Begin the password-reset flow by emailing a reset link (responds success regardless of whether the address exists, to avoid account...")
    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password reset link sent successfully", "TM_036"));
    }

    /**
     * Complete a password reset using the emailed reset token and a new password.
     *
     * @param request the validated reset request (token + new password)
     * @return an empty success envelope confirming the reset
     * @throws com.neo.chat.exception.UnauthorizedException if the reset token is invalid or expired
     * @throws com.neo.chat.exception.BadRequestException   if the new password fails policy checks
     */
    @Operation(summary = "Complete a password reset using the emailed reset token and a new password")
    @PostMapping(value = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password reset successful", "TM_037"));
    }

    /**
     * Change the authenticated user's password after verifying their current password.
     *
     * @param request     the validated change-password request (current + new password)
     * @param userDetails the authenticated principal
     * @return an empty success envelope confirming the change
     * @throws com.neo.chat.exception.UnauthorizedException if the current password is incorrect
     * @throws com.neo.chat.exception.BadRequestException   if the new password fails policy checks
     */
    @Operation(summary = "Change the authenticated user's password after verifying their current password")
    @PostMapping(value = "/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.changePassword(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password changed successfully", "TM_043"));
    }

    /**
     * Confirm an email-verification token (from the link in the verification email).
     *
     * @param request the validated request carrying the verification token
     * @return an empty success envelope confirming verification
     * @throws com.neo.chat.exception.UnauthorizedException if the token is invalid or expired
     */
    @Operation(summary = "Confirm an email-verification token (from the link in the verification email)")
    @PostMapping(value = "/verify-email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.getToken());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Email verified successfully", "TM_405"));
    }

    /**
     * Re-send the verification email to the authenticated (still-unverified) user.
     *
     * @param userDetails the authenticated principal
     * @return an empty success envelope confirming the email was sent
     * @throws com.neo.chat.exception.BadRequestException if the user's email is already verified
     */
    @Operation(summary = "Re-send the verification email to the authenticated (still-unverified) user")
    @PostMapping("/resend-verification")
    public ResponseEntity<ResponseDto<Void>> resendVerification(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.resendVerificationEmail(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Verification email sent", "TM_406"));
    }
}
