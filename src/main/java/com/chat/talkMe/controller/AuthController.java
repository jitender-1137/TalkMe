package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.ChangePasswordRequest;
import com.chat.talkMe.dto.request.ForgotPasswordRequest;
import com.chat.talkMe.dto.request.GuestLoginRequest;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.ResetPasswordRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.request.VerifyEmailRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.CaptchaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;

/**
 * Authentication and account/session management: signup, login (credentials + guest),
 * token refresh/logout, current-user profile, session revocation, and password/email flows.
 * Auth forms are CAPTCHA + honeypot gated; refresh/CSRF tokens are managed via HttpOnly cookies.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

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

    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

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
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Register a new user, then set refresh/CSRF auth cookies and return the login payload.
     *
     * @param request      the validated signup request (includes CAPTCHA token + honeypot)
     * @param userAgent    the caller's User-Agent header (recorded on the session)
     * @param httpRequest  the servlet request (used for client IP + human verification)
     * @param httpResponse the servlet response (auth cookies are written to it)
     * @return the {@link LoginResponse} with tokens and user profile
     * @throws com.chat.talkMe.exception.BadRequestException        if honeypot/CAPTCHA verification fails
     * @throws com.chat.talkMe.exception.ConflictException          if the username or email is taken
     * @throws com.chat.talkMe.exception.ContentModerationException if the chosen username fails moderation
     */
    @PostMapping("/signup")
    public ResponseEntity<ResponseDto<LoginResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        verifyHuman(request.getCaptchaToken(), request.getWebsite(), httpRequest);

        LoginResponse loginResponse = authService.signup(request, userAgent, httpRequest);

        setAuthCookies(httpResponse, loginResponse.getTokens().getRefreshToken(), false);

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
     * @throws com.chat.talkMe.exception.BadRequestException   if honeypot/CAPTCHA verification fails
     * @throws com.chat.talkMe.exception.UnauthorizedException if the credentials are invalid or the
     *                                                         account is deleted
     * @throws com.chat.talkMe.exception.ForbiddenException    if the account is suspended or a
     *                                                         non-guest account uses the guest flow (and vice versa)
     */
    @PostMapping("/login")
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
            LoginResponse response = authService.loginAsGuest(request, userAgent, httpRequest);
            setAuthCookies(httpResponse, response.getTokens().getRefreshToken(), true);
            return ResponseEntity.ok(SuccessResponseDto.success(response, "Login Successful", "TM_002"));
        } else {
            // Standard credentials login
            LoginRequest request = mapper.convertValue(parsed, LoginRequest.class);
            LoginResponse response = authService.login(request, userAgent, ip, httpRequest);
            setAuthCookies(httpResponse, response.getTokens().getRefreshToken(), false);
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
     * @throws com.chat.talkMe.exception.UnauthorizedException if the cookie is missing, or the
     *                                                         refresh token is invalid/expired
     */
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
        setAuthCookies(httpResponse, tokensResponse.getRefreshToken(), false);

        return ResponseEntity.ok(SuccessResponseDto.success(tokensResponse, "Token Refreshed Successfully", "TM_023"));
    }

    /**
     * Invalidate the current refresh token (if present) and clear the auth cookies.
     *
     * @param refreshToken the refresh token from the {@code refreshToken} cookie (maybe null/blank)
     * @param httpResponse the servlet response (auth cookies are cleared on it)
     * @return an empty success envelope confirming logout
     */
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
    @PutMapping("/me")
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
     * @throws com.chat.talkMe.exception.ForbiddenException if the session belongs to another user
     */
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
    @PostMapping("/forgot-password")
    public ResponseEntity<ResponseDto<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password reset link sent successfully", "TM_036"));
    }

    /**
     * Complete a password reset using the emailed reset token and a new password.
     *
     * @param request the validated reset request (token + new password)
     * @return an empty success envelope confirming the reset
     * @throws com.chat.talkMe.exception.UnauthorizedException if the reset token is invalid or expired
     * @throws com.chat.talkMe.exception.BadRequestException   if the new password fails policy checks
     */
    @PostMapping("/reset-password")
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
     * @throws com.chat.talkMe.exception.UnauthorizedException if the current password is incorrect
     * @throws com.chat.talkMe.exception.BadRequestException   if the new password fails policy checks
     */
    @PostMapping("/change-password")
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
     * @throws com.chat.talkMe.exception.UnauthorizedException if the token is invalid or expired
     */
    @PostMapping("/verify-email")
    public ResponseEntity<ResponseDto<Void>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.getToken());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Email verified successfully", "TM_405"));
    }

    /**
     * Re-send the verification email to the authenticated (still-unverified) user.
     *
     * @param userDetails the authenticated principal
     * @return an empty success envelope confirming the email was sent
     * @throws com.chat.talkMe.exception.BadRequestException if the user's email is already verified
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<ResponseDto<Void>> resendVerification(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.resendVerificationEmail(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Verification email sent", "TM_406"));
    }
}
