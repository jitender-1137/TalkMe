package com.neo.chat.service;

import com.neo.chat.dto.response.CountryDetectionResult;
import com.neo.chat.service.impl.CountryDetectionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link CountryDetectionServiceImpl} — country/geo resolution from an
 * inbound HTTP request. Manual Mockito wiring ({@code MockitoAnnotations.openMocks}) with the
 * collaborating {@link RestTemplate} mocked and config fields injected via
 * {@link org.springframework.test.util.ReflectionTestUtils}. Exercises the full resolution chain:
 * Cloudflare {@code CF-IPCountry} header, the configured/fallback proxy country headers, client-IP
 * resolution precedence ({@code CF-Connecting-IP} → {@code X-Forwarded-For} → {@code X-Real-IP} →
 * remote addr), local/private-IP bypass, and the ip-api GeoIP lookup with its success/failure and
 * field-coercion edge cases. Many methods carry inline comments naming the exact source branch they
 * backfill for coverage.
 */
class CountryDetectionServiceTest {

    @InjectMocks
    private CountryDetectionServiceImpl countryDetectionService;

    @Mock
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        com.neo.chat.util.ClientIp.setTrustedProxyHops(1);
        com.neo.chat.util.ClientIp.setTrustCloudflareHeader(false);
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(countryDetectionService, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(countryDetectionService, "proxyCountryHeader", "X-Country-Code");
    }

    @Test
    void testDetectCountry_withCloudflareHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-IPCountry", "IN");
        request.setRemoteAddr("103.21.244.5"); // Cloudflare range public IP

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("India", result.getCountry());
        assertEquals("Cloudflare Header", result.getSource());
    }

    @Test
    void testDetectCountry_withProxyHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Country-Code", "GB");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United Kingdom", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    @Test
    void testDetectCountry_withLocalIpBypass() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
        assertEquals("127.0.0.1", result.getClientIp());
    }

    @Test
    void testDetectCountry_withGeoIpSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");

        Map<String, Object> geoIpResponse = new HashMap<>();
        geoIpResponse.put("status", "success");
        geoIpResponse.put("country", "United States");
        geoIpResponse.put("countryCode", "US");

        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(geoIpResponse);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United States", result.getCountry());
        assertEquals("GeoIP", result.getSource());
        assertEquals("8.8.8.8", result.getClientIp());
    }

    @Test
    void testDetectCountry_withGeoIpFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");

        when(mockRestTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("API error or timeout"));

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
        assertEquals("8.8.8.8", result.getClientIp());
    }

    // ── null request ────────────────────────────────────────────────────────────

    @Test
    void testDetectCountry_nullRequest_returnsUnknownStub() {
        CountryDetectionResult result = countryDetectionService.detectCountry(null);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
        assertEquals("unknown", result.getClientIp());
    }

    // ── Cloudflare header edge cases ──────────────────────────────────────────────

    @Test
    void testDetectCountry_cloudflareXX_isIgnoredAndFallsThrough() {
        // "XX" is Cloudflare's "unknown" sentinel (case-insensitive) → must NOT be treated as a
        // country; with a local IP and no other signal we fall through to the Unknown stub.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-IPCountry", "xx");
        request.setRemoteAddr("127.0.0.1");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
    }

    // ── Proxy header fallback chain ───────────────────────────────────────────────

    @Test
    void testDetectCountry_proxyHeaderXCountryFallback() {
        // Neither the configured header nor X-Country-Code is present; the X-Country fallback wins.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Country", "FR");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("France", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    @Test
    void testDetectCountry_customConfiguredProxyHeader() {
        ReflectionTestUtils.setField(countryDetectionService, "proxyCountryHeader", "X-My-Geo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-My-Geo", "DE");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Germany", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    // ── resolveIp precedence ──────────────────────────────────────────────────────

    @Test
    void testResolveIp_prefersCfConnectingIpOnlyWhenTrustEnabled() {
        // CF-Connecting-IP is forgeable unless Cloudflare fronts all traffic — trusted only
        // when explicitly enabled (util.ClientIp / app.security.trust-cloudflare-header).
        com.neo.chat.util.ClientIp.setTrustCloudflareHeader(true);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("CF-Connecting-IP", "9.9.9.9");
            request.addHeader("X-Forwarded-For", "1.1.1.1");
            request.setRemoteAddr("2.2.2.2");
            when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

            CountryDetectionResult result = countryDetectionService.detectCountry(request);

            assertEquals("9.9.9.9", result.getClientIp());
        } finally {
            com.neo.chat.util.ClientIp.setTrustCloudflareHeader(false);
        }
    }

    @Test
    void testResolveIp_cfConnectingIpIgnoredByDefault() {
        // Default (no Cloudflare trust): CF-Connecting-IP is ignored; the proxy-appended
        // X-Forwarded-For hop is used instead.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "9.9.9.9");
        request.addHeader("X-Forwarded-For", "1.1.1.1");
        request.setRemoteAddr("2.2.2.2");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("1.1.1.1", result.getClientIp());
    }

    @Test
    void testResolveIp_usesProxyAppendedXForwardedForEntry() {
        // Only the rightmost hop (appended by the single trusted proxy) is trusted; the
        // client-supplied leading entries are ignored (spoofing defence).
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.9.9.9");
        request.setRemoteAddr("2.2.2.2");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("9.9.9.9", result.getClientIp());
    }

    @Test
    void testResolveIp_usesXRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "3.4.5.6");
        request.setRemoteAddr("2.2.2.2");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("3.4.5.6", result.getClientIp());
    }

    @Test
    void testResolveIp_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("4.5.6.7");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("4.5.6.7", result.getClientIp());
    }

    // ── GeoIP response edge cases ─────────────────────────────────────────────────

    @Test
    void testDetectCountry_geoIpNullResponse_returnsUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(null);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testDetectCountry_geoIpStatusNotSuccess_returnsUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        Map<String, Object> body = new HashMap<>();
        body.put("status", "fail");
        body.put("country", "Nowhere");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testDetectCountry_geoIpBlankCountry_returnsUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        Map<String, Object> body = new HashMap<>();
        body.put("status", "success");
        body.put("country", "   ");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testDetectCountry_geoIpPopulatesFineGrainedFields() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        Map<String, Object> body = new HashMap<>();
        body.put("status", "success");
        body.put("country", "India");
        body.put("countryCode", "IN");
        body.put("regionName", "Maharashtra");
        body.put("city", "Pune");
        body.put("lat", 18.52);      // Double
        body.put("lon", 73);         // Integer → asDouble must still coerce
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("India", result.getCountry());
        assertEquals("GeoIP", result.getSource());
        assertEquals("Pune", result.getCity());
        assertEquals("Maharashtra", result.getRegion());
        assertEquals("IN", result.getCountryCode());
        assertEquals(18.52, result.getLat(), 1e-9);
        assertEquals(73.0, result.getLon(), 1e-9);
    }

    @Test
    void testDetectCountry_geoIpNonNumericLatLon_coercesToNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        Map<String, Object> body = new HashMap<>();
        body.put("status", "success");
        body.put("country", "India");
        body.put("lat", "not-a-number");
        body.put("lon", null);
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("India", result.getCountry());
        assertNull(result.getLat());
        assertNull(result.getLon());
    }

    @Test
    void testDetectCountry_localIpWithGeolocateLocalIpEnabled_doesLookup() {
        // In dev mode (geolocateLocalIp=true) a local IP is geolocated (against the server's own
        // public IP) instead of bailing to Unknown.
        ReflectionTestUtils.setField(countryDetectionService, "geolocateLocalIp", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United States", result.getCountry());
        assertEquals("GeoIP", result.getSource());
        assertEquals("127.0.0.1", result.getClientIp());
    }

    // ── isLocalOrPrivateIp coverage ───────────────────────────────────────────────

    @Test
    void testPrivateIp_tenBlock_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testPrivateIp_oneNineTwoBlock_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.10.10");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testPrivateIp_172_16_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.5.5");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testPrivateIp_172_31_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.31.255.1");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testIpv6Loopback_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
        assertEquals("::1", result.getClientIp());
    }

    @Test
    void testPublic172_15_isNotPrivate_attemptsGeoIp() {
        // 172.15.x.x is BELOW the private 172.16–31 range → public → GeoIP lookup runs.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.15.0.1");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("GeoIP", result.getSource());
        assertEquals("172.15.0.1", result.getClientIp());
    }

    @Test
    void testPublic172_32_isNotPrivate_attemptsGeoIp() {
        // 172.32.x.x is ABOVE the private range → public.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.32.0.1");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("GeoIP", result.getSource());
    }

    // ── Cloudflare / proxy compound-guard branch backfill ────────────────────────

    @Test
    void testDetectCountry_cloudflareBlankHeader_fallsThrough() {
        // CF-IPCountry present but blank → isBlank() short-circuits the CF branch (line 53).
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-IPCountry", "   ");
        request.setRemoteAddr("127.0.0.1");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testDetectCountry_configuredHeaderBlank_thenXCountryCodeWins() {
        // Configured header present but BLANK → line 65 isBlank() true → enters fallback block;
        // X-Country-Code is present and non-blank → inner line 68 is false (skips X-Country).
        ReflectionTestUtils.setField(countryDetectionService, "proxyCountryHeader", "X-My-Geo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-My-Geo", "   ");
        request.addHeader("X-Country-Code", "GB");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("United Kingdom", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    @Test
    void testDetectCountry_xCountryCodeBlank_thenXCountryFallbackWins() {
        // Configured header absent → line 65 null true → enter; X-Country-Code present but BLANK →
        // inner line 68 isBlank() true → falls to the X-Country fallback.
        ReflectionTestUtils.setField(countryDetectionService, "proxyCountryHeader", "X-My-Geo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Country-Code", "   ");
        request.addHeader("X-Country", "FR");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("France", result.getCountry());
        assertEquals("Proxy Header", result.getSource());
    }

    @Test
    void testDetectCountry_allProxyHeadersBlank_fallsThroughToGeoIp() {
        // Every proxy header resolves to a blank string → line 72 (proxyCountryCode != null but
        // isBlank()) is false → the proxy branch is skipped and GeoIP runs.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Country-Code", "   ");
        request.addHeader("X-Country", "   ");
        request.setRemoteAddr("8.8.8.8");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("GeoIP", result.getSource());
    }

    @Test
    void testDetectCountry_geoIpNullCountryKey_returnsUnknown() {
        // status=success but no "country" key → country == null → line 105 first condition false.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        Map<String, Object> body = new HashMap<>();
        body.put("status", "success");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(body);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getCountry());
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void testGetCountryNameFromCode_invalidCode_fallsBackToRawCode() {
        // "999" is an unassigned UN M.49 region → getDisplayCountry returns the code (or blank);
        // either way the guard at line 163 is false and line 169 returns the raw code.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-IPCountry", "999");
        request.setRemoteAddr("8.8.8.8");

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("999", result.getCountry());
        assertEquals("Cloudflare Header", result.getSource());
    }

    // ── resolveIp blank-header fall-through (lines 134/139/144) ───────────────────

    @Test
    void testResolveIp_blankCfConnectingIp_fallsToXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "   ");
        request.addHeader("X-Forwarded-For", "1.1.1.1");
        request.setRemoteAddr("2.2.2.2");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("1.1.1.1", result.getClientIp());
    }

    @Test
    void testResolveIp_blankXForwardedFor_fallsToXRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.addHeader("X-Real-IP", "3.3.3.3");
        request.setRemoteAddr("2.2.2.2");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("3.3.3.3", result.getClientIp());
    }

    @Test
    void testResolveIp_blankXRealIp_fallsToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "   ");
        request.setRemoteAddr("4.4.4.4");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("4.4.4.4", result.getClientIp());
    }

    // ── isLocalOrPrivateIp remaining branches (line 173/183/187) ──────────────────

    @Test
    void testNullClientIp_isTreatedAsLocal() {
        // No proxy headers + null remoteAddr → resolveIp returns null → isLocalOrPrivateIp(null)
        // short-circuits true (line 173 ip == null) → bypassed to Unknown.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("Unknown", result.getSource());
        assertNull(result.getClientIp());
    }

    @Test
    void testLongFormIpv6Loopback_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
        assertEquals("0:0:0:0:0:0:0:1", result.getClientIp());
    }

    @Test
    void testLiteralLocalhost_isBypassed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("localhost");
        CountryDetectionResult result = countryDetectionService.detectCountry(request);
        assertEquals("Unknown", result.getSource());
    }

    @Test
    void test172WithTooFewOctets_isNotPrivate_attemptsGeoIp() {
        // "172." starts with the 172 prefix but split yields a single element → line 183
        // (parts.length >= 2) is false → not private → GeoIP lookup runs.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("GeoIP", result.getSource());
    }

    @Test
    void test172WithNonNumericOctet_swallowsParseError_isNotPrivate() {
        // Second octet is non-numeric → Integer.parseInt throws → caught (lines 187-189) →
        // falls through to return false → treated as public → GeoIP lookup runs.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.zz.0.1");
        when(mockRestTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(successGeo());

        CountryDetectionResult result = countryDetectionService.detectCountry(request);

        assertEquals("GeoIP", result.getSource());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static Map<String, Object> successGeo() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "success");
        body.put("country", "United States");
        body.put("countryCode", "US");
        return body;
    }
}
