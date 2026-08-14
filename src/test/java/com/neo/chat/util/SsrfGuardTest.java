package com.neo.chat.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link SsrfGuard}. The block/allow decision is exercised with
 * IP-literal hosts (so {@code InetAddress.getAllByName} resolves them locally without any
 * DNS lookup), giving deterministic, network-free coverage of every blocked range:
 * loopback, any-local, link-local (incl. the {@code 169.254.169.254} metadata endpoint),
 * site-local/private, multicast, and IPv6 unique-local (fc00::/7). A genuine public IP is
 * allowed. Scheme, malformed-URL, missing-host and unresolvable-host guards are covered too.
 */
@DisplayName("SsrfGuard (unit)")
class SsrfGuardTest {

    @Nested
    @DisplayName("assertSafe – input guards")
    class AssertSafeInputGuards {

        @Test
        @DisplayName("null URL is rejected")
        void shouldRejectNullUrl() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL is required");
        }

        @Test
        @DisplayName("empty URL is rejected")
        void shouldRejectEmptyUrl() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL is required");
        }

        @Test
        @DisplayName("blank/whitespace URL is rejected")
        void shouldRejectBlankUrl() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL is required");
        }

        @Test
        @DisplayName("malformed URL (illegal characters) is rejected")
        void shouldRejectMalformedUrl() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("http://exa mple.com/path"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Malformed URL");
        }
    }

    @Nested
    @DisplayName("assertSafe – scheme guard")
    class AssertSafeSchemeGuard {

        @Test
        @DisplayName("missing scheme is rejected")
        void shouldRejectMissingScheme() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("example.com/path"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only http(s) URLs are allowed");
        }

        @Test
        @DisplayName("ftp scheme is rejected")
        void shouldRejectFtpScheme() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("ftp://8.8.8.8/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only http(s) URLs are allowed");
        }

        @Test
        @DisplayName("file scheme is rejected")
        void shouldRejectFileScheme() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("file:///etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only http(s) URLs are allowed");
        }

        @Test
        @DisplayName("scheme comparison is case-insensitive (HTTP allowed)")
        void shouldAllowUppercaseHttpScheme() {
            assertThatCode(() -> SsrfGuard.assertSafe("HTTP://8.8.8.8/x"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("assertSafe – host guards")
    class AssertSafeHostGuards {

        @Test
        @DisplayName("missing host is rejected")
        void shouldRejectMissingHost() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("http:relativePath"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL host is missing");
        }

        @Test
        @DisplayName("unresolvable host is rejected")
        void shouldRejectUnresolvableHost() {
            assertThatThrownBy(() -> SsrfGuard.assertSafe("http://no-such-host.invalid/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL host cannot be resolved");
        }
    }

    @Nested
    @DisplayName("assertSafe – blocked address ranges")
    class AssertSafeBlockedRanges {

        @Test
        @DisplayName("IPv4 loopback (127.0.0.1) is blocked")
        void shouldBlockIpv4Loopback() {
            assertBlocked("http://127.0.0.1/");
        }

        @Test
        @DisplayName("IPv6 loopback ([::1]) is blocked")
        void shouldBlockIpv6Loopback() {
            assertBlocked("http://[::1]/");
        }

        @Test
        @DisplayName("any-local (0.0.0.0) is blocked")
        void shouldBlockAnyLocal() {
            assertBlocked("http://0.0.0.0/");
        }

        @Test
        @DisplayName("link-local cloud-metadata endpoint (169.254.169.254) is blocked")
        void shouldBlockMetadataEndpoint() {
            assertBlocked("http://169.254.169.254/latest/meta-data/");
        }

        @Test
        @DisplayName("private 10.0.0.0/8 (site-local) is blocked")
        void shouldBlockPrivate10() {
            assertBlocked("http://10.0.0.218:8080/");
        }

        @Test
        @DisplayName("private 172.16.0.0/12 (site-local) is blocked")
        void shouldBlockPrivate172() {
            assertBlocked("http://172.16.0.1/");
        }

        @Test
        @DisplayName("private 192.168.0.0/16 (site-local) is blocked")
        void shouldBlockPrivate192() {
            assertBlocked("http://192.168.1.1/");
        }

        @Test
        @DisplayName("multicast (224.0.0.1) is blocked")
        void shouldBlockMulticast() {
            assertBlocked("http://224.0.0.1/");
        }

        @Test
        @DisplayName("IPv6 unique-local fc00::/7 (fd..) is blocked")
        void shouldBlockIpv6UniqueLocal() {
            assertBlocked("http://[fd12:3456:789a:1::1]/");
        }

        private void assertBlocked(String url) {
            assertThatThrownBy(() -> SsrfGuard.assertSafe(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL resolves to a non-public address");
        }
    }

    @Nested
    @DisplayName("assertSafe – public host allowed")
    class AssertSafePublicAllowed {

        @Test
        @DisplayName("public IPv4 over http passes")
        void shouldAllowPublicIpv4Http() {
            assertThatCode(() -> SsrfGuard.assertSafe("http://8.8.8.8/x")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("public IPv4 over https passes")
        void shouldAllowPublicIpv4Https() {
            assertThatCode(() -> SsrfGuard.assertSafe("https://1.1.1.1/x")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed before validation")
        void shouldTrimBeforeValidating() {
            assertThatCode(() -> SsrfGuard.assertSafe("  https://1.1.1.1/x  ")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("assertSafeHttps")
    class AssertSafeHttps {

        @Test
        @DisplayName("public https URL passes")
        void shouldAllowPublicHttps() {
            assertThatCode(() -> SsrfGuard.assertSafeHttps("https://8.8.8.8/x")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("http URL is rejected even when otherwise safe")
        void shouldRejectHttpWhenHttpsRequired() {
            assertThatThrownBy(() -> SsrfGuard.assertSafeHttps("http://8.8.8.8/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only https URLs are allowed");
        }

        @Test
        @DisplayName("https scheme match is case-insensitive")
        void shouldAllowUppercaseHttps() {
            assertThatCode(() -> SsrfGuard.assertSafeHttps("HTTPS://8.8.8.8/x")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("unsafe (blocked) https host still rejected by the underlying assertSafe")
        void shouldRejectBlockedHostFirst() {
            assertThatThrownBy(() -> SsrfGuard.assertSafeHttps("https://127.0.0.1/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL resolves to a non-public address");
        }
    }

    @Nested
    @DisplayName("openStream – validates before connecting")
    class OpenStream {

        @Test
        @DisplayName("rejects a null URL via the guard (no connection attempted)")
        void shouldRejectNullBeforeConnecting() {
            assertThatThrownBy(() -> SsrfGuard.openStream(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL is required");
        }

        @Test
        @DisplayName("rejects a loopback URL via the guard (no connection attempted)")
        void shouldRejectBlockedHostBeforeConnecting() {
            assertThatThrownBy(() -> SsrfGuard.openStream("http://127.0.0.1/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL resolves to a non-public address");
        }

        @Test
        @DisplayName("rejects a non-http scheme via the guard (no connection attempted)")
        void shouldRejectBadSchemeBeforeConnecting() {
            assertThatThrownBy(() -> SsrfGuard.openStream("ftp://8.8.8.8/x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only http(s) URLs are allowed");
        }
    }

    @Nested
    @DisplayName("assertSafe – public IPv6 allowed")
    class AssertSafePublicIpv6Allowed {

        // A globally-routable IPv6 literal (Google public DNS) reaches isUniqueLocalIpv6 with a
        // 16-byte address whose first byte is NOT in fc00::/7, exercising that helper's false branch
        // (the fd.. case already covers the true branch). No DNS is performed for an IP literal.
        @Test
        @DisplayName("public IPv6 (2001:4860:4860::8888) passes and is not treated as unique-local")
        void shouldAllowPublicIpv6() {
            assertThatCode(() -> SsrfGuard.assertSafe("http://[2001:4860:4860::8888]/x"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("public IPv6 over https passes assertSafeHttps")
        void shouldAllowPublicIpv6Https() {
            assertThatCode(() -> SsrfGuard.assertSafeHttps("https://[2001:4860:4860::8888]/x"))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Exercises the connection body of {@link SsrfGuard#openStream(String)} — the redirect-refusal,
     * unexpected-status and success paths — without any external network. A loopback
     * {@link HttpServer} plays the origin; a JVM-scoped {@code http.proxy*} setting routes the
     * request for a guard-approved <em>public</em> IP literal (so {@code assertSafe} passes) through
     * that loopback proxy. Every byte therefore stays on {@code 127.0.0.1}; the guard still sees a
     * public host, so the code under test runs exactly as in production.
     */
    @Nested
    @DisplayName("openStream – connection body (loopback origin behind a proxy)")
    class OpenStreamConnectionBody {

        private static final String PUBLIC_HOST = "http://8.8.8.8";

        private HttpServer server;
        private String prevProxyHost;
        private String prevProxyPort;
        private String prevNonProxyHosts;

        @BeforeEach
        void startServerAndRouteThroughLoopback() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                switch (path) {
                    case "/ok" -> {
                        byte[] body = "payload-bytes".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                    }
                    case "/redirect" -> {
                        exchange.getResponseHeaders().add("Location", "http://10.0.0.1/internal");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    }
                    default -> {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    }
                }
            });
            server.start();

            int port = server.getAddress().getPort();
            prevProxyHost = System.getProperty("http.proxyHost");
            prevProxyPort = System.getProperty("http.proxyPort");
            prevNonProxyHosts = System.getProperty("http.nonProxyHosts");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(port));
            System.setProperty("http.nonProxyHosts", "");
        }

        @AfterEach
        void restorePropsAndStopServer() {
            restore("http.proxyHost", prevProxyHost);
            restore("http.proxyPort", prevProxyPort);
            restore("http.nonProxyHosts", prevNonProxyHosts);
            if (server != null) {
                server.stop(0);
            }
        }

        private void restore(String key, String prev) {
            if (prev == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prev);
            }
        }

        @Test
        @DisplayName("a 200 response yields the body stream (redirects disabled, GET issued)")
        void shouldReturnStreamOnHttpOk() throws IOException {
            try (InputStream in = SsrfGuard.openStream(PUBLIC_HOST + "/ok")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload-bytes");
            }
        }

        @Test
        @DisplayName("a 3xx redirect is refused rather than followed to an internal host")
        void shouldRefuseToFollowRedirect() {
            assertThatThrownBy(() -> SsrfGuard.openStream(PUBLIC_HOST + "/redirect"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Refusing to follow redirect")
                    .hasMessageContaining(PUBLIC_HOST + "/redirect");
        }

        @Test
        @DisplayName("a non-200 (500) status is rejected with the status in the message")
        void shouldRejectUnexpectedStatus() {
            assertThatThrownBy(() -> SsrfGuard.openStream(PUBLIC_HOST + "/boom"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Unexpected status 500")
                    .hasMessageContaining(PUBLIC_HOST + "/boom");
        }

        @Test
        @DisplayName("the guard still rejects a blocked host before any connection is made")
        void shouldStillRejectBlockedHostBeforeConnecting() {
            assertThatThrownBy(() -> SsrfGuard.openStream("http://127.0.0.1/ok"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("URL resolves to a non-public address");
        }
    }
}
