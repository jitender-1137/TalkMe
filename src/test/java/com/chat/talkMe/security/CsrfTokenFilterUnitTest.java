package com.chat.talkMe.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pure filter unit test for {@link CsrfTokenFilter} — constructs the filter directly and invokes
 * {@code doFilterInternal(request, response, chain)} with Spring mock servlet objects, asserting the
 * double-submit-cookie CSRF contract (cookie {@code csrf_token} must equal header {@code X-CSRF-Token}
 * for mutating methods on protected paths) and every skip / mismatch edge case. No MockMvc, no Spring
 * context — the security filter is exercised in isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CsrfTokenFilter (unit)")
class CsrfTokenFilterUnitTest {

    private static final String COOKIE_NAME = "csrf_token";
    private static final String HEADER_NAME = "X-CSRF-Token";
    private static final String PROTECTED_PATH = "/api/v1/messages/send";
    private static final String EXPECTED_MESSAGE = "CSRF token mismatch or missing. Action rejected.";
    private static final String EXPECTED_CODE = "CSRF_TOKEN_INVALID";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CsrfTokenFilter filter;

    @Mock
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new CsrfTokenFilter();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod(method);
        req.setRequestURI(uri);
        return req;
    }

    private void withCookie(MockHttpServletRequest req, String name, String value) {
        req.setCookies(new Cookie(name, value));
    }

    /** Invoke the (package-visible, protected) filter method directly. */
    private MockHttpServletResponse invoke(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, chain);
        return res;
    }

    private void assertPassedThrough(MockHttpServletResponse res) throws Exception {
        // Filter did not touch the response status (default 200) and delegated to the chain exactly once.
        verify(chain, times(1)).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private void assertRejected(MockHttpServletResponse res) throws Exception {
        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(res.getContentType()).isEqualTo("application/json");

        JsonNode body = objectMapper.readTree(res.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("message").asText()).isEqualTo(EXPECTED_MESSAGE);
        assertThat(body.get("messageCode").asText()).isEqualTo(EXPECTED_CODE);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Safe (non-mutating) methods — CSRF check is skipped entirely
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Safe methods are skipped")
    class SafeMethods {

        @Test
        void shouldPassGetOnProtectedPathWithNoToken() throws Exception {
            MockHttpServletResponse res = invoke(request("GET", PROTECTED_PATH));
            assertPassedThrough(res);
        }

        @Test
        void shouldPassHeadOnProtectedPathWithNoToken() throws Exception {
            MockHttpServletResponse res = invoke(request("HEAD", PROTECTED_PATH));
            assertPassedThrough(res);
        }

        @Test
        void shouldPassOptionsOnProtectedPathWithNoToken() throws Exception {
            MockHttpServletResponse res = invoke(request("OPTIONS", PROTECTED_PATH));
            assertPassedThrough(res);
        }

        @Test
        void shouldPassGetEvenWhenTokensAreMismatched() throws Exception {
            // Proves the method gate is applied BEFORE any token comparison.
            MockHttpServletRequest req = request("GET", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "cookie-value");
            req.addHeader(HEADER_NAME, "totally-different");
            assertPassedThrough(invoke(req));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Excluded paths — mutating method allowed through without a token
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Excluded paths are skipped")
    class ExcludedPaths {

        @Test
        void shouldPassLoginPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/login")));
        }

        @Test
        void shouldPassSignupPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/signup")));
        }

        @Test
        void shouldPassRefreshPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/refresh")));
        }

        @Test
        void shouldPassForgotPasswordPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/forgot-password")));
        }

        @Test
        void shouldPassResetPasswordPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/reset-password")));
        }

        @Test
        void shouldPassPushDeliveredPostWithoutToken() throws Exception {
            assertPassedThrough(invoke(request("POST", "/api/v1/push/delivered")));
        }

        @Test
        void shouldPassExcludedPathWithTrailingSubPath() throws Exception {
            // isExcluded uses startsWith, so future sub-paths under an excluded prefix also match.
            assertPassedThrough(invoke(request("POST", "/api/v1/auth/login/extra")));
        }

        @Test
        void shouldPassExcludedPathWhenApiVersionPrefixAbsent() throws Exception {
            // isExcluded normalizes away the /api/v1 prefix on both sides before matching.
            assertPassedThrough(invoke(request("POST", "/auth/login")));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Mutating methods on protected paths WITH matching token — allowed through
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Matching double-submit token on protected path passes")
    class MatchingToken {

        @Test
        void shouldPassPost() throws Exception {
            assertPassedThrough(invokeWithMatchingToken("POST"));
        }

        @Test
        void shouldPassPut() throws Exception {
            assertPassedThrough(invokeWithMatchingToken("PUT"));
        }

        @Test
        void shouldPassPatch() throws Exception {
            assertPassedThrough(invokeWithMatchingToken("PATCH"));
        }

        @Test
        void shouldPassDelete() throws Exception {
            assertPassedThrough(invokeWithMatchingToken("DELETE"));
        }

        private MockHttpServletResponse invokeWithMatchingToken(String method) throws Exception {
            MockHttpServletRequest req = request(method, PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "abc-123-token");
            req.addHeader(HEADER_NAME, "abc-123-token");
            return invoke(req);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Mismatch / missing token on protected mutating request — rejected 403
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mismatch / missing token is rejected with 403")
    class RejectedRequests {

        @Test
        void shouldRejectWhenHeaderPresentCookieMissing() throws Exception {
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            req.addHeader(HEADER_NAME, "only-header");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenCookiePresentHeaderMissing() throws Exception {
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "only-cookie");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenBothPresentButDifferent() throws Exception {
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "cookie-value");
            req.addHeader(HEADER_NAME, "header-value");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenBothMissing() throws Exception {
            assertRejected(invoke(request("POST", PROTECTED_PATH)));
        }

        @Test
        void shouldRejectPutWithMismatch() throws Exception {
            MockHttpServletRequest req = request("PUT", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "a");
            req.addHeader(HEADER_NAME, "b");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectDeleteWithMissingToken() throws Exception {
            assertRejected(invoke(request("DELETE", PROTECTED_PATH)));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Blank / whitespace / wrong-cookie edge cases — treated as "no token"
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank / whitespace / wrong-name tokens are rejected")
    class BlankAndWhitespaceEdges {

        @Test
        void shouldRejectWhenBothTokensEmptyStrings() throws Exception {
            // Equal values, but StringUtils.hasText("") is false → rejected.
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "");
            req.addHeader(HEADER_NAME, "");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenBothTokensWhitespaceOnly() throws Exception {
            // Equal values, but StringUtils.hasText("   ") is false → rejected.
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "   ");
            req.addHeader(HEADER_NAME, "   ");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenCookieBlankButHeaderValid() throws Exception {
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, COOKIE_NAME, "");
            req.addHeader(HEADER_NAME, "real-token");
            assertRejected(invoke(req));
        }

        @Test
        void shouldRejectWhenOnlyUnrelatedCookieNamePresent() throws Exception {
            // A differently-named cookie (even with a matching value) must not satisfy the check.
            MockHttpServletRequest req = request("POST", PROTECTED_PATH);
            withCookie(req, "XSRF-TOKEN", "match-me");
            req.addHeader(HEADER_NAME, "match-me");
            assertRejected(invoke(req));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Error response body shape
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("403 body carries the CSRF error DTO and JSON content type")
    void shouldWriteExpectedErrorBodyOnRejection() throws Exception {
        MockHttpServletResponse res = invoke(request("POST", PROTECTED_PATH));

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(res.getContentType()).isEqualTo("application/json");

        JsonNode body = objectMapper.readTree(res.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("message").asText()).isEqualTo(EXPECTED_MESSAGE);
        assertThat(body.get("messageCode").asText()).isEqualTo(EXPECTED_CODE);
        // data is never populated on the error path.
        assertThat(body.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("Chain is invoked and never blocked on a valid mutating request")
    void shouldNotWriteAnyBodyOnPassThrough() throws Exception {
        MockHttpServletRequest req = request("POST", PROTECTED_PATH);
        withCookie(req, COOKIE_NAME, "same-token");
        req.addHeader(HEADER_NAME, "same-token");

        MockHttpServletResponse res = invoke(req);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(res.getContentAsString()).isEmpty();
    }
}
