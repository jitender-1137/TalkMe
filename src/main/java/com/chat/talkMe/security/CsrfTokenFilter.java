package com.chat.talkMe.security;

import com.chat.talkMe.dto.response.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Double-submit-cookie CSRF filter. For state-changing methods (POST/PUT/PATCH/DELETE) that are
 * not on the exclusion list, it requires the {@code X-CSRF-Token} header to be present and to equal
 * the {@code csrf_token} cookie; a mismatch or absence yields a 403 JSON error
 * ({@code CSRF_TOKEN_INVALID}). Safe methods and excluded public/auth endpoints pass through.
 */
@Component
public class CsrfTokenFilter extends OncePerRequestFilter {

    private static final Set<String> CSRF_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * Paths excluded from CSRF validation.
     * These are all public endpoints where no CSRF cookie exists yet
     * (unauthenticated callers, no prior login cookie issued).
     * <p>
     * Rules:
     * - /api/v1/auth/login      — no cookie exists yet, issues cookie after login
     * - /api/v1/auth/signup     — no cookie exists yet, issues cookie after signup
     * - /api/v1/auth/refresh    — uses HttpOnly refreshToken cookie, not CSRF-dependent
     * - /api/v1/auth/forgot-password  — public, unauthenticated, no cookie issued yet
     * - /api/v1/auth/reset-password   — public, unauthenticated, uses one-time token in body
     * - /api/v1/push/delivered        — POSTed by the service worker (closed-tab push
     * delivery ack); it has no access to the csrf_token cookie/header and is instead
     * authorized by the signed delivery token in the body, so CSRF doesn't apply.
     */
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/push/delivered"
    );

    // Reuse ObjectMapper — it is thread-safe and expensive to construct per-request
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Enforces double-submit CSRF validation on non-excluded state-changing requests, writing a 403
     * JSON error and short-circuiting the chain on a missing/mismatched token; otherwise continues.
     *
     * @param request     the incoming HTTP request
     * @param response    the HTTP response
     * @param filterChain the remaining filter chain
     * @throws ServletException if chain processing fails
     * @throws IOException      if writing the error or chain processing fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        if (CSRF_METHODS.contains(method) && !isExcluded(path)) {
            String headerToken = request.getHeader("X-CSRF-Token");
            String cookieToken = getCsrfTokenFromCookies(request);

            if (!StringUtils.hasText(headerToken) || !StringUtils.hasText(cookieToken) || !headerToken.equals(cookieToken)) {
                sendCsrfError(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Uses startsWith() so that paths with trailing slashes or future sub-paths
     * are still matched correctly against the exclusion list.
     * Normalizes paths by removing the /api/v1 prefix if present for prefix-agnostic matching.
     *
     * @param path the request URI to test
     * @return {@code true} if the path is exempt from CSRF validation
     */
    private boolean isExcluded(String path) {
        String normalizedPath = path.startsWith("/api/v1") ? path.substring(7) : path;
        for (String excluded : EXCLUDED_PATHS) {
            String normalizedExcluded = excluded.startsWith("/api/v1") ? excluded.substring(7) : excluded;
            if (normalizedPath.startsWith(normalizedExcluded)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code csrf_token} cookie value from the request.
     *
     * @param request the incoming HTTP request
     * @return the cookie value, or {@code null} if the cookie is absent
     */
    private String getCsrfTokenFromCookies(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("csrf_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Writes a 403 JSON error response ({@code CSRF_TOKEN_INVALID}) for a rejected request.
     *
     * @param response the HTTP response to write into
     * @throws IOException if writing the response body fails
     */
    private void sendCsrfError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");

        ResponseDto<Void> responseDto = ResponseDto.error(
                "CSRF token mismatch or missing. Action rejected.",
                "CSRF_TOKEN_INVALID"
        );

        response.getWriter().write(objectMapper.writeValueAsString(responseDto));
    }
}
