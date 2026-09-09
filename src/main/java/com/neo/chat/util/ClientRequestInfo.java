package com.neo.chat.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plain, servlet-free snapshot of the request facts the auth/geo services need: the caller's
 * User-Agent, the proxy-aware client IP (see {@link ClientIp}) and the request headers
 * (case-insensitive lookup). Built once in the web layer via {@link #from(HttpServletRequest)}
 * so services never depend on {@code jakarta.servlet} types.
 *
 * <p>Credential-bearing headers ({@code Authorization}, {@code Proxy-Authorization},
 * {@code Cookie}) are never copied — no service needs them and the snapshot must be safe to
 * pass around.</p>
 *
 * @param userAgent the caller's User-Agent header (may be null)
 * @param clientIp  the resolved client IP (never null when built via {@code from})
 * @param headers   request headers; lookups through {@link #header(String)} are case-insensitive
 */
public record ClientRequestInfo(String userAgent, String clientIp, Map<String, String> headers) {

    private static final Set<String> EXCLUDED_HEADERS = Set.of("authorization", "proxy-authorization", "cookie");

    public ClientRequestInfo {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) copy.put(k, v);
            });
        }
        headers = Collections.unmodifiableMap(copy);
    }

    /**
     * Case-insensitive header lookup.
     *
     * @param name the header name
     * @return the header value, or null when absent
     */
    public String header(String name) {
        return name == null ? null : headers.get(name);
    }

    /**
     * Snapshot a servlet request, taking the User-Agent from its header and resolving the client
     * IP proxy-aware via {@link ClientIp#resolve(HttpServletRequest)}.
     *
     * @param request the servlet request (may be null)
     * @return the snapshot, or null when the request is null
     */
    public static ClientRequestInfo from(HttpServletRequest request) {
        return from(request, request == null ? null : request.getHeader("User-Agent"));
    }

    /**
     * Snapshot a servlet request with an explicit User-Agent (e.g. one already bound by the
     * controller), resolving the client IP proxy-aware.
     *
     * @param request   the servlet request (may be null)
     * @param userAgent the User-Agent to record
     * @return the snapshot, or null when the request is null
     */
    public static ClientRequestInfo from(HttpServletRequest request, String userAgent) {
        return from(request, userAgent, ClientIp.resolve(request));
    }

    /**
     * Snapshot a servlet request with an explicit User-Agent and client IP.
     *
     * @param request   the servlet request (may be null)
     * @param userAgent the User-Agent to record
     * @param clientIp  the client IP to record
     * @return the snapshot, or null when the request is null
     */
    public static ClientRequestInfo from(HttpServletRequest request, String userAgent, String clientIp) {
        if (request == null) return null;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name == null || EXCLUDED_HEADERS.contains(name.toLowerCase())) continue;
                String value = request.getHeader(name);
                if (value != null) headers.put(name, value);
            }
        }
        return new ClientRequestInfo(userAgent, clientIp, headers);
    }
}
