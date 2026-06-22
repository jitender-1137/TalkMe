package com.chat.talkMe.controller;

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
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.CaptchaService;
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

    @PostMapping("/login")
    public ResponseEntity<ResponseDto<LoginResponse>> login(
            @RequestBody String bodyRaw, // Allows parsing dynamic requests for guest mode
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ip = getClientIp(httpRequest);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid login json structure");
        }
    }

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

    @GetMapping("/me")
    public ResponseEntity<ResponseDto<AuthUserResponse>> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        AuthUserResponse response = authService.getCurrentUser(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PutMapping("/me")
    public ResponseEntity<ResponseDto<AuthUserResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AuthUserResponse response = authService.updateProfile(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Profile updated successfully", "TM_060"));
    }

    @GetMapping("/sessions")
    public ResponseEntity<ResponseDto<List<SessionResponse>>> getSessions(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<SessionResponse> response = authService.getSessions(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<ResponseDto<Void>> revokeSession(
            @PathVariable("id") String sessionUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.revokeSession(sessionUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Session terminated successfully", "TM_051"));
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<ResponseDto<Void>> revokeAllSessions(@AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.revokeAllSessions(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All other sessions revoked successfully", "TM_052"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ResponseDto<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password reset link sent successfully", "TM_036"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ResponseDto<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password reset successful", "TM_037"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ResponseDto<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.changePassword(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Password changed successfully", "TM_043"));
    }
}
