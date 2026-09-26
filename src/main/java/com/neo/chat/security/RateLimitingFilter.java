package com.neo.chat.security;

import com.neo.chat.config.RateLimitProperties;
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
import java.util.regex.Pattern;

/**
 * Redis-backed fixed-window HTTP rate limiter. Skipped entirely in local/dev/test profiles and for
 * CORS preflight, WebSocket handshakes, and non-{@code /api/} static requests.
 *
 * <p>Each eligible request is checked against TWO fixed-window buckets (see
 * {@link RateLimitProperties}), both keyed by the SUBJECT — the authenticated username, or the
 * resolved client IP when anonymous:
 * <ol>
 *   <li><b>Global</b> — one bucket across all endpoints ({@code auth-limit} / {@code anon-limit}),
 *       a coarse abuse backstop.</li>
 *   <li><b>Per-endpoint</b> — one bucket per subject + normalized {@code METHOD /route}
 *       ({@code endpoint-default}, overridable per route), so a burst on a single endpoint can't
 *       drain the whole quota.</li>
 * </ol>
 * On breach it returns 429 with a {@code Retry-After} header; if Redis is unavailable it fails open.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final Environment env;
    private final RateLimitProperties rl;

    /**
     * Path segments that identify a specific RESOURCE rather than a route (UUIDs, numeric ids, long
     * hex/opaque tokens). Masked to {@code :id} when normalizing a path so all requests to the same
     * route share one per-endpoint bucket (not one bucket per chat/message/user).
     */
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+|[0-9a-fA-F]{16,})$");

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
        if (!rl.isEnabled()
                || env.acceptsProfiles(Profiles.of("local", "default", "dev", "test"))
                || env.getActiveProfiles().length == 0) {
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
        // anonymous. Counting those meant ~2-3 reloads (≈ the anon quota)
        // tripped the limiter even though the user made no real API calls.
        // Everything outside /api/** (pages, chunks, images, manifest) is static
        // and must not consume the quota.
        if (path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine the rate-limit SUBJECT:
        // • Prefer authenticated username so users behind shared NAT/VPN don't burn each other's quota.
        // • Fall back to IP for unauthenticated requests.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null && auth.isAuthenticated()
                && !(auth.getPrincipal() instanceof String s && s.equals("anonymousUser"));

        String subject = isAuthenticated ? "user:" + auth.getName() : "ip:" + resolveClientIp(request);
        int globalLimit = isAuthenticated ? rl.getAuthLimit() : rl.getAnonLimit();

        // Per-endpoint bucket keyed by METHOD + normalized route (path IDs masked). A burst on one
        // endpoint (e.g. bulk actions) is capped on its own without draining the whole quota, and
        // sensitive routes can be tightened via app.security.rate-limit.endpoints.*
        String methodRoute = request.getMethod() + " " + normalizeRoute(path);
        int endpointLimit = rl.endpointLimit(methodRoute);

        try {
            // 1) Global backstop (all endpoints, per subject).
            long ttl = overLimitTtl("rate:limit:" + subject, globalLimit);
            if (ttl >= 0) {
                log.warn("Rate limit (global) exceeded for {} on {}", subject, methodRoute);
                rejectWith(response, ttl);
                return;
            }
            // 2) Per-endpoint bucket (this subject + this route).
            ttl = overLimitTtl("rate:ep:" + subject + ":" + methodRoute, endpointLimit);
            if (ttl >= 0) {
                log.warn("Rate limit (endpoint {}={}) exceeded for {}", methodRoute, endpointLimit, subject);
                rejectWith(response, ttl);
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
     * Increment a fixed-window counter (creating + expiring it on first hit) and report whether it
     * has now exceeded {@code limit}.
     *
     * @return the window's remaining TTL in seconds when OVER the limit, else {@code -1} (allowed).
     */
    private long overLimitTtl(String key, int limit) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, rl.getWindowSeconds(), TimeUnit.SECONDS);
        }
        if (count != null && count > limit) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return ttl != null && ttl > 0 ? ttl : rl.getWindowSeconds();
        }
        return -1L;
    }

    /** Short-circuit the request with a 429 + {@code Retry-After}. */
    private void rejectWith(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader("Retry-After",
                String.valueOf(retryAfterSeconds > 0 ? retryAfterSeconds : rl.getWindowSeconds()));
        sendRateLimitError(response);
    }

    /**
     * Normalize a request path into a stable ROUTE key by masking resource-id segments (UUIDs,
     * numeric ids, long opaque tokens) to {@code :id}, so all requests to the same route share one
     * per-endpoint bucket instead of one per resource. e.g.
     * {@code /api/v1/chats/<uuid>/messages → /api/v1/chats/:id/messages}.
     *
     * @param path the request URI (no query string)
     * @return the normalized route, always starting with {@code /}
     */
    static String normalizeRoute(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder(path.length());
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(ID_SEGMENT.matcher(seg).matches() ? ":id" : seg);
        }
        return sb.length() == 0 ? "/" : sb.toString();
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
