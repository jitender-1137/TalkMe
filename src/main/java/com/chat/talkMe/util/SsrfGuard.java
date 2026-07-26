package com.chat.talkMe.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Server-Side Request Forgery guard for the few places the backend fetches a
 * client-supplied URL (post/story soundtrack download, web-push endpoints).
 *
 * <p>It rejects non-http(s) schemes and any URL whose host resolves to a
 * loopback, link-local (incl. the {@code 169.254.169.254} cloud metadata
 * endpoint), site-local/private, any-local, or multicast address — the targets
 * an attacker would use to reach internal services or instance metadata.
 *
 * <p>{@link #openStream(String)} additionally disables redirect following so a
 * public URL can't 302 to an internal one. A residual DNS-rebinding window
 * remains (the host is resolved once for the check and again by the JDK when
 * connecting); acceptable for this app's blind-SSRF threat model.
 */
@Slf4j
public final class SsrfGuard {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private SsrfGuard() {}

    /** Throws {@link IllegalArgumentException} if {@code rawUrl} is unsafe to fetch. */
    public static void assertSafe(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http(s) URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is missing");
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL host cannot be resolved");
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                throw new IllegalArgumentException("URL resolves to a non-public address");
            }
        }
    }

    /** Same as {@link #assertSafe} but additionally requires HTTPS. */
    public static void assertSafeHttps(String rawUrl) {
        assertSafe(rawUrl);
        if (!rawUrl.trim().regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException("Only https URLs are allowed");
        }
    }

    /** Validates the URL then opens a non-redirecting stream to it. */
    public static InputStream openStream(String rawUrl) throws IOException {
        assertSafe(rawUrl);
        HttpURLConnection conn = (HttpURLConnection) URI.create(rawUrl.trim()).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        if (status >= 300 && status < 400) {
            conn.disconnect();
            throw new IOException("Refusing to follow redirect from " + rawUrl);
        }
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("Unexpected status " + status + " from " + rawUrl);
        }
        return conn.getInputStream();
    }

    private static boolean isBlocked(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()
                || isUniqueLocalIpv6(addr);
    }

    /** IPv6 Unique Local Addresses (fc00::/7) aren't covered by isSiteLocalAddress. */
    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
