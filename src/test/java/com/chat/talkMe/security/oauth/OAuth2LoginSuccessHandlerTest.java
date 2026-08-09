package com.chat.talkMe.security.oauth;

import com.chat.talkMe.dto.OAuthUserInfo;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OAuth2LoginSuccessHandler} — maps the Google OIDC principal onto a
 * local account via {@link AuthService#oauthLogin}, sets the HttpOnly refresh + CSRF cookies,
 * and redirects the SPA to {@code /#chats}. Covers attribute mapping (incl. the name-from-
 * given/family fallback and email_verified normalization), the best-effort People-API age/
 * gender path (present, no authorized-client-service, no client, fetch failure, non-OAuth2
 * token), cookie wiring and the redirect target trimming.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2LoginSuccessHandler (unit)")
class OAuth2LoginSuccessHandlerTest {

    @Mock private AuthService authService;
    @Mock private GoogleProfileService googleProfileService;
    @Mock private ObjectProvider<OAuth2AuthorizedClientService> authorizedClientServiceProvider;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private RedirectStrategy redirectStrategy;

    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(authService, googleProfileService,
                authorizedClientServiceProvider);
        ReflectionTestUtils.setField(handler, "cookieSecure", false);
        ReflectionTestUtils.setField(handler, "cookieSameSite", "Lax");
        ReflectionTestUtils.setField(handler, "frontendBaseUrl", "http://localhost:3000");
        handler.setRedirectStrategy(redirectStrategy);
    }

    /** OAuth2User with the standard OIDC attributes; individual tests override as needed. */
    private OAuth2User principalWithName(String name) {
        OAuth2User p = Mockito.mock(OAuth2User.class);
        lenient().when(p.getAttribute("sub")).thenReturn("google-sub-1");
        lenient().when(p.getAttribute("email")).thenReturn("user@example.com");
        lenient().when(p.getAttribute("picture")).thenReturn("https://pic/x.png");
        lenient().when(p.getAttribute("email_verified")).thenReturn(Boolean.TRUE);
        lenient().when(p.getAttribute("name")).thenReturn(name);
        return p;
    }

    private OAuth2AuthenticationToken tokenWith(OAuth2User principal) {
        OAuth2AuthenticationToken token = Mockito.mock(OAuth2AuthenticationToken.class);
        lenient().when(token.getPrincipal()).thenReturn(principal);
        lenient().when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        lenient().when(token.getName()).thenReturn("google-sub-1");
        return token;
    }

    private void stubLogin() {
        LoginResponse login = LoginResponse.builder()
                .tokens(JwtTokensResponse.builder().refreshToken("refresh-tok").build())
                .build();
        when(authService.oauthLogin(any(OAuthUserInfo.class), any(), eq(request))).thenReturn(login);
    }

    private OAuthUserInfo captureInfo() {
        ArgumentCaptor<OAuthUserInfo> cap = ArgumentCaptor.forClass(OAuthUserInfo.class);
        verify(authService).oauthLogin(cap.capture(), any(), eq(request));
        return cap.getValue();
    }

    @Nested
    @DisplayName("profile mapping")
    class Mapping {

        @Test
        @DisplayName("full profile with granted People-API scopes → maps age + gender + cookies + redirect")
        void fullSuccess() throws Exception {
            OAuth2User principal = principalWithName("Ada Lovelace");
            OAuth2AuthenticationToken token = tokenWith(principal);

            OAuth2AuthorizedClientService svc = Mockito.mock(OAuth2AuthorizedClientService.class);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(svc);
            OAuth2AuthorizedClient client = Mockito.mock(OAuth2AuthorizedClient.class);
            when(svc.loadAuthorizedClient("google", "google-sub-1")).thenReturn(client);
            OAuth2AccessToken accessToken = Mockito.mock(OAuth2AccessToken.class);
            when(client.getAccessToken()).thenReturn(accessToken);
            when(accessToken.getTokenValue()).thenReturn("access-tok");
            when(googleProfileService.fetch("access-tok"))
                    .thenReturn(new GoogleProfileService.Extended(27, "female"));
            when(request.getHeader("User-Agent")).thenReturn("JUnit-UA");
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            OAuthUserInfo info = captureInfo();
            assertThat(info.getProviderId()).isEqualTo("google-sub-1");
            assertThat(info.getEmail()).isEqualTo("user@example.com");
            assertThat(info.isEmailVerified()).isTrue();
            assertThat(info.getName()).isEqualTo("Ada Lovelace");
            assertThat(info.getPicture()).isEqualTo("https://pic/x.png");
            assertThat(info.getAge()).isEqualTo(27);
            assertThat(info.getGender()).isEqualTo("female");

            // two auth cookies set
            ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
            verify(response, Mockito.times(2))
                    .addHeader(eq(HttpHeaders.SET_COOKIE), cookies.capture());
            List<String> values = cookies.getAllValues();
            assertThat(values).anyMatch(c -> c.startsWith("refreshToken=refresh-tok"));
            assertThat(values).anyMatch(c -> c.startsWith("csrf_token="));
            assertThat(values).allMatch(c -> c.contains("HttpOnly") || c.startsWith("csrf_token="));

            verify(redirectStrategy).sendRedirect(request, response, "http://localhost:3000/#chats");
        }

        @Test
        @DisplayName("blank name → falls back to given_name + family_name")
        void nameFallback() throws Exception {
            OAuth2User principal = principalWithName("   ");
            when(principal.getAttribute("given_name")).thenReturn("Grace");
            when(principal.getAttribute("family_name")).thenReturn("Hopper");
            OAuth2AuthenticationToken token = tokenWith(principal);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            assertThat(captureInfo().getName()).isEqualTo("Grace Hopper");
        }

        @Test
        @DisplayName("null name with only given_name → trims to the given name")
        void nameFallbackPartial() throws Exception {
            OAuth2User principal = principalWithName(null);
            when(principal.getAttribute("given_name")).thenReturn("Solo");
            when(principal.getAttribute("family_name")).thenReturn(null);
            OAuth2AuthenticationToken token = tokenWith(principal);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            assertThat(captureInfo().getName()).isEqualTo("Solo");
        }

        @Test
        @DisplayName("email_verified null → emailVerified false")
        void emailVerifiedNull() throws Exception {
            OAuth2User principal = principalWithName("Ada");
            when(principal.getAttribute("email_verified")).thenReturn(null);
            OAuth2AuthenticationToken token = tokenWith(principal);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            assertThat(captureInfo().isEmailVerified()).isFalse();
        }
    }

    @Nested
    @DisplayName("best-effort People-API enrichment")
    class Enrichment {

        @Test
        @DisplayName("authorized-client service unavailable → age/gender null, fetch never called")
        void noClientService() throws Exception {
            OAuth2AuthenticationToken token = tokenWith(principalWithName("Ada"));
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            OAuthUserInfo info = captureInfo();
            assertThat(info.getAge()).isNull();
            assertThat(info.getGender()).isNull();
            verify(googleProfileService, never()).fetch(any());
        }

        @Test
        @DisplayName("no authorized client for the user → age/gender null, fetch never called")
        void noAuthorizedClient() throws Exception {
            OAuth2AuthenticationToken token = tokenWith(principalWithName("Ada"));
            OAuth2AuthorizedClientService svc = Mockito.mock(OAuth2AuthorizedClientService.class);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(svc);
            when(svc.loadAuthorizedClient("google", "google-sub-1")).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            assertThat(captureInfo().getAge()).isNull();
            verify(googleProfileService, never()).fetch(any());
        }

        @Test
        @DisplayName("People-API fetch throws → swallowed, login still proceeds with null age/gender")
        void fetchFailureNonFatal() throws Exception {
            OAuth2AuthenticationToken token = tokenWith(principalWithName("Ada"));
            OAuth2AuthorizedClientService svc = Mockito.mock(OAuth2AuthorizedClientService.class);
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(svc);
            OAuth2AuthorizedClient client = Mockito.mock(OAuth2AuthorizedClient.class);
            when(svc.loadAuthorizedClient("google", "google-sub-1")).thenReturn(client);
            OAuth2AccessToken accessToken = Mockito.mock(OAuth2AccessToken.class);
            when(client.getAccessToken()).thenReturn(accessToken);
            when(accessToken.getTokenValue()).thenReturn("access-tok");
            when(googleProfileService.fetch("access-tok")).thenThrow(new RuntimeException("boom"));
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            OAuthUserInfo info = captureInfo();
            assertThat(info.getAge()).isNull();
            assertThat(info.getGender()).isNull();
            verify(redirectStrategy).sendRedirect(request, response, "http://localhost:3000/#chats");
        }

        @Test
        @DisplayName("plain Authentication (not OAuth2AuthenticationToken) → no enrichment attempted")
        void nonOAuth2Token() throws Exception {
            Authentication auth = Mockito.mock(Authentication.class);
            // Build the principal BEFORE opening the getPrincipal() stub — principalWithName()
            // performs its own stubbing, which would nest inside an in-progress when(...).
            OAuth2User principal = principalWithName("Ada");
            when(auth.getPrincipal()).thenReturn(principal);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, auth);

            assertThat(captureInfo().getAge()).isNull();
            verify(authorizedClientServiceProvider, never()).getIfAvailable();
            verify(googleProfileService, never()).fetch(any());
        }
    }

    @Nested
    @DisplayName("cookies + redirect")
    class CookiesAndRedirect {

        @Test
        @DisplayName("cookieSecure=true → refresh cookie carries Secure")
        void secureCookies() throws Exception {
            ReflectionTestUtils.setField(handler, "cookieSecure", true);
            OAuth2AuthenticationToken token = tokenWith(principalWithName("Ada"));
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
            verify(response, Mockito.times(2))
                    .addHeader(eq(HttpHeaders.SET_COOKIE), cookies.capture());
            assertThat(cookies.getAllValues())
                    .filteredOn(c -> c.startsWith("refreshToken="))
                    .allMatch(c -> c.contains("Secure"));
        }

        @Test
        @DisplayName("frontend base url with trailing slashes → trimmed before appending /#chats")
        void trimsTrailingSlashes() throws Exception {
            ReflectionTestUtils.setField(handler, "frontendBaseUrl", "https://app.talkme.fun///");
            OAuth2AuthenticationToken token = tokenWith(principalWithName("Ada"));
            when(authorizedClientServiceProvider.getIfAvailable()).thenReturn(null);
            stubLogin();

            handler.onAuthenticationSuccess(request, response, token);

            verify(redirectStrategy).sendRedirect(request, response, "https://app.talkme.fun/#chats");
        }
    }
}
