package com.neo.chat.security;

import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.util.ClientIp;
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
 * IP ({@code ANON_LIMIT} req/window), over a {@code WINDOW_SECONDS} window. On breach, it returns 429
 * with a
 * {@code Retry-After} header; if Redis is unavailable it fails open (allows the request).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final Environment env;

    // Authenticated users: 100 req/min (chat apps are inherently chatty — sync, read receipts, typing)
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

        // Skip rate limiting entirely in local/dev/test profiles. NOTE: "dev" MUST be
        // listed — it's the profile IntelliJ/local runs use, and without it the limiter
        // throttled ordinary localhost traffic (a single browser reconnect/bootstrap makes
        // dozens of /api calls, all sharing the one ::1 bucket → "Rate limit exceeded" spam
        // even with no page really open). Only "prod" is rate-limited.
        if (env.acceptsProfiles(Profiles.of("local", "default", "dev", "test")) || env.getActiveProfiles().length == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // Don't count CORS preflight or the WebSocket/STOMP handshake — the WS
        // connection is authenticated at the STOMP CONNECT frame, and reconnects
        // would otherwise burn the HTTP quota.
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isWebSocketHandshake(path)) {
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
     * Exact match for the STOMP handshake endpoints ({@code /ws}, {@code /api/v1/ws} and their
     * SockJS sub-paths). A previous {@code path.contains("/ws")} substring test also exempted any
     * API route whose path merely contained "ws" (e.g. {@code /api/v1/follows},
     * {@code /api/v1/profile-views}), silently disabling the limiter for those endpoints.
     *
     * @param path the request URI
     * @return {@code true} only for the WebSocket handshake paths
     */
    static boolean isWebSocketHandshake(String path) {
        if (path == null) return false;
        return path.equals("/ws") || path.startsWith("/ws/")
                || path.equals("/api/v1/ws") || path.startsWith("/api/v1/ws/");
    }

    /**
     * Resolve the real client IP behind the reverse proxy.
     *
     * <p>SECURITY: the {@code X-Forwarded-For} header is a comma-separated list where every proxy
     * <em>appends</em> the address it saw. The LEFTMOST entries are supplied by the client itself
     * and are therefore attacker-controlled; only the RIGHTMOST {@code trustedProxyHops} entries
     * were written by infrastructure we control. Reading the first entry (the previous behaviour)
     * let a client rotate {@code X-Forwarded-For: <random>} on every request and get a fresh,
     * unlimited rate-limit bucket — bypassing login/signup/guest throttling entirely. We now read
     * the entry {@code trustedProxyHops} from the right (default 1 = a single reverse proxy such as
     * Nginx Proxy Manager), so the value is the address the trusted proxy observed. With
     * {@code app.security.trusted-proxy-hops=0} proxy headers are ignored and the TCP peer address
     * is used (correct when the app is exposed directly).
     *
     * @param request the incoming HTTP request
     * @return the client IP as observed by the outermost trusted proxy, else {@code X-Real-IP},
     * else the TCP peer address
     */
    private String resolveClientIp(HttpServletRequest request) {
        return ClientIp.resolve(request, trustedProxyHops());
    }

    /**
     * Number of trusted reverse-proxy hops in front of the app ({@code app.security.trusted-proxy-hops},
     * default 1). Read through {@link Environment} so the filter's constructor signature is unchanged.
     */
    private int trustedProxyHops() {
        try {
            Integer v = env.getProperty("app.security.trusted-proxy-hops", Integer.class);
            return v == null ? 1 : v;
        } catch (Exception e) {
            return 1;
        }
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
