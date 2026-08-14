package com.neo.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a per-request correlation id into the SLF4J {@link MDC} under {@code requestId} so every log
 * line for a request can be traced (see {@code logging.pattern.level} in application.yml). The id is
 * the inbound {@code X-Request-Id} header when present and safe (alphanumeric/dot/dash/underscore,
 * &le; 64 chars — sanitised to prevent log injection), otherwise a fresh random UUID. The chosen id
 * is echoed back on the {@code X-Request-Id} response header, and the MDC entry is always cleared in
 * a {@code finally} block so it never leaks to another request on a pooled thread.
 *
 * <p>Registered (via {@code RequestIdFilterConfig}) at {@code Ordered.HIGHEST_PRECEDENCE} so it runs
 * BEFORE the Spring Security filter chain — the id is then present while JWT/auth filters log.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC key referenced by the {@code %X{requestId}} conversion word in the log pattern. */
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    /** Request/response header carrying the correlation id across hops. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final int MAX_ID_LENGTH = 64;

    /**
     * Resolve the request id (inbound header if safe, else a new UUID), bind it to the MDC and the
     * response header for the duration of the request, then clear the MDC entry.
     *
     * @param request     the current HTTP request
     * @param response     the current HTTP response (gets the {@code X-Request-Id} header)
     * @param filterChain the remaining filter chain to continue
     * @throws jakarta.servlet.ServletException if a downstream filter/servlet fails
     * @throws java.io.IOException               if an I/O error occurs during processing
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    /**
     * Return a sanitised inbound id, or a new UUID when the header is missing/blank/unsafe. Only
     * {@code [A-Za-z0-9._-]} up to {@link #MAX_ID_LENGTH} chars are accepted (blocks CR/LF and other
     * log-injection payloads).
     *
     * @param headerValue the raw inbound {@code X-Request-Id} header (may be null)
     * @return a safe correlation id, never null
     */
    private String resolveRequestId(String headerValue) {
        if (headerValue != null) {
            String trimmed = headerValue.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_ID_LENGTH
                    && trimmed.matches("[A-Za-z0-9._-]+")) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
