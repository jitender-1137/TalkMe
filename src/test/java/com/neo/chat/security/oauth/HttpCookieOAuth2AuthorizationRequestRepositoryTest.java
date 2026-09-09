package com.neo.chat.security.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link HttpCookieOAuth2AuthorizationRequestRepository} — the cookie-backed
 * store that keeps the in-flight OAuth2 authorization request (state/PKCE) alive across the
 * stateless redirect round-trip. Covers save (with/without null request), load (present /
 * absent / tampered), remove (present vs absent), the serialize→deserialize round-trip, and
 * the cookie-attribute wiring (HttpOnly, Secure, SameSite, Max-Age).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpCookieOAuth2AuthorizationRequestRepository (unit)")
class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

    private static final String COOKIE_NAME =
            HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private HttpCookieOAuth2AuthorizationRequestRepository repo;

    @BeforeEach
    void setUp() {
        repo = new HttpCookieOAuth2AuthorizationRequestRepository(false, "Lax");
    }

    private static OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-123")
                .redirectUri("https://app.example.com/callback")
                .scope("openid", "profile")
                .state("state-xyz")
                .build();
    }

    /**
     * Runs save, extracts the base64 cookie value from the Set-Cookie header.
     */
    private String saveAndCapture(OAuth2AuthorizationRequest authRequest) {
        repo.saveAuthorizationRequest(authRequest, request, response);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cap.capture());
        String header = cap.getValue();
        String prefix = COOKIE_NAME + "=";
        return header.substring(prefix.length(), header.indexOf(';'));
    }

    @Nested
    @DisplayName("saveAuthorizationRequest")
    class Save {

        @Test
        @DisplayName("non-null request → sets a Set-Cookie with 180s max-age and HttpOnly")
        void savesCookie() {
            repo.saveAuthorizationRequest(sampleRequest(), request, response);

            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cap.capture());
            String header = cap.getValue();
            assertThat(header).startsWith(COOKIE_NAME + "=");
            assertThat(header).contains("Max-Age=180");
            assertThat(header).contains("Path=/");
            assertThat(header).contains("HttpOnly");
            assertThat(header).contains("SameSite=Lax");
        }

        @Test
        @DisplayName("cookieSecure=true → cookie carries the Secure attribute")
        void secureFlag() {
            repo = new HttpCookieOAuth2AuthorizationRequestRepository(true, "Lax");

            repo.saveAuthorizationRequest(sampleRequest(), request, response);

            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cap.capture());
            assertThat(cap.getValue()).contains("Secure");
        }

        @Test
        @DisplayName("null request → expires the cookie (Max-Age=0) instead of storing")
        void nullRequestExpires() {
            repo.saveAuthorizationRequest(null, request, response);

            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cap.capture());
            assertThat(cap.getValue()).contains("Max-Age=0");
            assertThat(cap.getValue()).startsWith(COOKIE_NAME + "=");
        }
    }

    @Nested
    @DisplayName("loadAuthorizationRequest")
    class Load {

        @Test
        @DisplayName("valid cookie → deserializes the stored authorization request")
        void loadsStored() {
            String value = saveAndCapture(sampleRequest());
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, value)});

            OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(request);

            assertThat(loaded).isNotNull();
            assertThat(loaded.getState()).isEqualTo("state-xyz");
            assertThat(loaded.getClientId()).isEqualTo("client-123");
            assertThat(loaded.getRedirectUri()).isEqualTo("https://app.example.com/callback");
        }

        @Test
        @DisplayName("no cookies on request → null")
        void noCookies() {
            when(request.getCookies()).thenReturn(null);
            assertThat(repo.loadAuthorizationRequest(request)).isNull();
        }

        @Test
        @DisplayName("cookie with a different name → null")
        void otherCookieOnly() {
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("some_other", "v")});
            assertThat(repo.loadAuthorizationRequest(request)).isNull();
        }

        @Test
        @DisplayName("tampered/garbage cookie value → null (treated as no in-flight request)")
        void tamperedValue() {
            when(request.getCookies())
                    .thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, "not-valid-base64-$$$")});
            assertThat(repo.loadAuthorizationRequest(request)).isNull();
        }
    }

    @Nested
    @DisplayName("removeAuthorizationRequest")
    class Remove {

        @Test
        @DisplayName("present → returns the request and expires the cookie")
        void presentRemoves() {
            String value = saveAndCapture(sampleRequest());
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, value)});

            OAuth2AuthorizationRequest removed = repo.removeAuthorizationRequest(request, response);

            assertThat(removed).isNotNull();
            assertThat(removed.getState()).isEqualTo("state-xyz");
            // second addHeader on the same response is the expiry cookie (Max-Age=0)
            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            verify(response, Mockito.atLeastOnce())
                    .addHeader(eq(HttpHeaders.SET_COOKIE), cap.capture());
            assertThat(cap.getAllValues()).anyMatch(h -> h.contains("Max-Age=0"));
        }

        @Test
        @DisplayName("absent → returns null and writes no expiry cookie")
        void absentNoExpiry() {
            when(request.getCookies()).thenReturn(null);

            OAuth2AuthorizationRequest removed = repo.removeAuthorizationRequest(request, response);

            assertThat(removed).isNull();
            verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("serialize → deserialize round-trip")
    class RoundTrip {

        @Test
        @DisplayName("state, scopes and additional parameters survive the cookie round-trip")
        void roundTrip() {
            OAuth2AuthorizationRequest original = sampleRequest();
            String value = saveAndCapture(original);
            when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, value)});

            OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(request);

            assertThat(loaded.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
            assertThat(loaded.getScopes()).isEqualTo(original.getScopes());
            assertThat(loaded.getGrantType()).isEqualTo(original.getGrantType());
        }
    }
}
