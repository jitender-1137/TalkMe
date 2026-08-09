package com.chat.talkMe.security;

import com.chat.talkMe.dto.response.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed fixed-window HTTP rate limiter. Skipped entirely in local/dev/test profiles and for
 * CORS preflight, WebSocket handshakes, and non-{@code /api/} static requests. Counts per
 * authenticated username ({@code AUTH_LIMIT} req/window) or, when anonymous, per resolved client
 * IP ({@code ANON_LIMIT} req/window), over a {@code WINDOW_SECONDS} window. On breach it returns 429
 * with a
 * {@code Retry-After} header; if Redis is unavailable it fails open (allows the request).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final Environment env;

    // Authenticated users: 300 req/min (chat apps are inherently chatty — sync, read receipts, typing)
    private static final int AUTH_LIMIT = 100;
    // Anonymous / unauthenticated requests: 60 req/min (login, signup, etc.)
    private static final int ANON_LIMIT = 60;
    private static final int WINDOW_SECONDS = 60;

    /**
     * Applies the fixed-window rate limit to eligible {@code /api/} requests, short-circuiting with
     * a 429 JSON error and {@code Retry-After} header on breach; skips non-API/preflight/WS/dev
     * traffic and fails open when Redis errors.
     *
     * @param request     the incoming HTTP request
     * @param response    the HTTP response
     * @param filterChain the remaining filter chain
     * @throws ServletException if chain processing fails
     * @throws IOException      if writing the error or chain processing fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Skip rate limiting entirely in local/dev/test profiles
        if (env.acceptsProfiles(Profiles.of("local", "default", "test")) || env.getActiveProfiles().length == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // Don't count CORS preflight or the WebSocket/STOMP handshake — the WS
        // connection is authenticated at the STOMP CONNECT frame, and reconnects
        // would otherwise burn the HTTP quota.
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || (path != null && path.contains("/ws"))) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only rate-limit API calls. The Next.js frontend is bundled into this
        // same app's static resources, so a single page load fetches the HTML
        // plus dozens of /_next/** JS & CSS chunks — all served from "/" and all
        // anonymous. Counting those meant ~2-3 reloads (≈ ANON_LIMIT requests)
        // tripped the limiter even though the user made no real API calls.
        // Everything outside /api/** (pages, chunks, images, manifest) is static
        // and must not consume the quota.
        if (path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine the rate-limit key:
        // • Prefer authenticated username so users behind shared NAT/VPN don't burn each other's quota.
        // • Fall back to IP for unauthenticated requests.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null && auth.isAuthenticated()
                && !(auth.getPrincipal() instanceof String s && s.equals("anonymousUser"));

        int limit;
        String key;
        if (isAuthenticated) {
            key = "rate:limit:user:" + auth.getName();
            limit = AUTH_LIMIT;
        } else {
            String ip = resolveClientIp(request);
            key = "rate:limit:ip:" + ip;
            limit = ANON_LIMIT;
        }

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            if (count != null && count > limit) {
                log.warn("Rate limit exceeded for key={} count={}", key, count);
                // Inform the client how long to wait
                Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                response.setHeader("Retry-After", String.valueOf(ttl != null && ttl > 0 ? ttl : WINDOW_SECONDS));
                sendRateLimitError(response);
                return;
            }
        } catch (Exception e) {
            // Redis unavailable – fail open to avoid locking out users
            log.error("Redis error in RateLimitingFilter, failing open", e);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolve the real client IP, respecting common reverse-proxy headers.
     * Only trusts the first non-private entry so that a user cannot spoof the header.
     *
     * @param request the incoming HTTP request
     * @return the first {@code X-Forwarded-For} entry, else {@code X-Real-IP}, else the remote addr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Writes a 429 JSON error response ({@code TM_007}) for a rate-limited request.
     *
     * @param response the HTTP response to write into
     * @throws IOException if writing the response body fails
     */
    private void sendRateLimitError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");

        ResponseDto<Void> responseDto = ResponseDto.error(
                "Too many requests. Please slow down.",
                "TM_007"
        );

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(responseDto));
    }
}
