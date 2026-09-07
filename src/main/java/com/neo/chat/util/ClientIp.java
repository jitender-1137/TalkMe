package com.neo.chat.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single, proxy-aware client-IP resolver shared by every IP-keyed security control (HTTP rate
 * limiting, login brute-force lockout, CAPTCHA remote-ip, consent audit, geo detection).
 *
 * <p>SECURITY: {@code X-Forwarded-For} is a comma-separated list to which every proxy APPENDS
 * the peer address it saw. The leftmost entries are supplied by the client and are therefore
 * attacker-controlled; only the rightmost {@code trustedProxyHops} entries were written by
 * infrastructure we control. Reading the first entry (the historic behavior in several places)
 * let a client rotate a forged header per request and defeat every per-IP limit. This helper
 * reads the entry {@code trustedProxyHops} from the right (default 1 = a single reverse proxy such
 * as Nginx Proxy Manager). {@code CF-Connecting-IP} is honored only when explicitly enabled via
 * {@link #setTrustCloudflareHeader(boolean)} (it is trivially forgeable when Cloudflare is not in
 * front of the proxy). With {@code trustedProxyHops = 0} all proxy headers are ignored.
 */
public final class ClientIp {

    private static volatile int trustedProxyHops = 1;
    private static volatile boolean trustCloudflareHeader = false;

    private ClientIp() {
    }

    /** Configure the number of trusted reverse-proxy hops (bound from {@code app.security.trusted-proxy-hops}). */
    public static void setTrustedProxyHops(int hops) {
        trustedProxyHops = Math.max(0, hops);
    }

    /** Whether {@code CF-Connecting-IP} may be trusted (bound from {@code app.security.trust-cloudflare-header}). */
    public static void setTrustCloudflareHeader(boolean trust) {
        trustCloudflareHeader = trust;
    }

    public static int getTrustedProxyHops() {
        return trustedProxyHops;
    }

    /**
     * Resolve the client IP using the globally configured trusted-hop count.
     *
     * @param request the incoming request
     * @return the client IP as observed by the outermost trusted proxy, else the TCP peer address
     */
    public static String resolve(HttpServletRequest request) {
        return resolve(request, trustedProxyHops);
    }

    /**
     * Resolve the client IP for an explicit trusted-hop count.
     *
     * @param request the incoming request
     * @param hops    number of trusted reverse proxies in front of the app; {@code <= 0} ignores headers
     * @return the client IP
     */
    public static String resolve(HttpServletRequest request, int hops) {
        if (request == null) return "unknown";
        if (hops <= 0) {
            return request.getRemoteAddr();
        }
        if (trustCloudflareHeader) {
            String cf = request.getHeader("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) return cf.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            int idx = Math.max(0, parts.length - hops);
            String candidate = parts[idx].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        // X-Real-IP is SET (not appended) by the proxy to the peer it saw, so it is trustworthy
        // whenever the proxy is the only path to this port.
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
