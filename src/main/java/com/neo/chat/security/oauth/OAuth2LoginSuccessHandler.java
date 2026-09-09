package com.neo.chat.security.oauth;

import com.neo.chat.util.ClientRequestInfo;
import com.neo.chat.dto.OAuthUserInfo;
import com.neo.chat.dto.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.neo.chat.controller.AuthController;
import com.neo.chat.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Runs after Google authenticates the user. Maps the OIDC profile onto a local
 * account (create/link via {@link OAuthLoginPort#oauthLogin}), sets the same HttpOnly
 * refresh-token + CSRF cookies the password login issues, then redirects to the SPA.
 * The SPA boots, calls {@code /auth/me} → {@code /auth/refresh} (cookie) and lands
 * signed in — no token is ever placed in the URL.
 */
@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuthLoginPort oauthLoginPort;
    private final GoogleProfileService googleProfileService;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService;
    private final JwtTokenProvider tokenProvider;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final String frontendBaseUrl;

    /**
     * Constructor-injects the collaborators and the cookie / redirect settings.
     *
     * @param oauthLoginPort             creates/links the local account
     * @param googleProfileService    best-effort People API profile fetch
     * @param authorizedClientService access to the Google access token (may be absent)
     * @param tokenProvider           mints the media-read cookie token
     * @param cookieSecure            {@code app.cookie.secure} (default false)
     * @param cookieSameSite          {@code app.cookie.same-site} (default Lax)
     * @param frontendBaseUrl         {@code app.frontend-base-url} (default http://localhost:3000)
     */
    public OAuth2LoginSuccessHandler(OAuthLoginPort oauthLoginPort,
                                     GoogleProfileService googleProfileService,
                                     ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService,
                                     JwtTokenProvider tokenProvider,
                                     @Value("${app.cookie.secure:false}") boolean cookieSecure,
                                     @Value("${app.cookie.same-site:Lax}") String cookieSameSite,
                                     @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.oauthLoginPort = oauthLoginPort;
        this.googleProfileService = googleProfileService;
        this.authorizedClientService = authorizedClientService;
        this.tokenProvider = tokenProvider;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * Handles a successful Google authentication: extracts OIDC profile attributes, best-effort
     * fetches age/gender via the People API, creates/links the local account through
     * {@link OAuthLoginPort#oauthLogin}, sets the HttpOnly refresh-token + CSRF cookies, and redirects
     * to the SPA's {@code #chats} deep link (no token in the URL).
     *
     * @param request        the OAuth callback request
     * @param response       the response used to set cookies and redirect
     * @param authentication the successful OAuth authentication (an {@link OAuth2AuthenticationToken})
     * @throws IOException if issuing the redirect fails
     */
    @Override
    public void onAuthenticationSuccess(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();

        assert principal != null : "OAuth2 authentication must carry an OAuth2User principal";
        String sub = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        String picture = principal.getAttribute("picture");
        Boolean emailVerified = principal.getAttribute("email_verified");
        String name = principal.getAttribute("name");
        if (name == null || name.isBlank()) {
            String given = principal.getAttribute("given_name");
            String family = principal.getAttribute("family_name");
            name = ((given != null ? given : "") + " " + (family != null ? family : "")).trim();
        }

        // Best-effort age/gender (only if the People-API scopes were granted).
        Integer age = null;
        String gender = null;
        try {
            if (authentication instanceof OAuth2AuthenticationToken token) {
                OAuth2AuthorizedClientService svc = authorizedClientService.getIfAvailable();
                if (svc != null) {
                    OAuth2AuthorizedClient client = svc.loadAuthorizedClient(
                            token.getAuthorizedClientRegistrationId(), token.getName());
                    if (client != null && client.getAccessToken() != null) {
                        GoogleProfileService.Extended ext =
                                googleProfileService.fetch(client.getAccessToken().getTokenValue());
                        // Leave null when Google doesn't share them — the app forces the
                        // user to pick age (18–99) + gender on first entry, so we must NOT
                        // fabricate defaults here.
                        age = ext.age();
                        gender = ext.gender();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Extended Google profile unavailable: {}", e.getMessage());
        }

        OAuthUserInfo info = OAuthUserInfo.builder()
                .providerId(sub)
                .email(email)
                .emailVerified(Boolean.TRUE.equals(emailVerified))
                .name(name)
                .picture(picture)
                .age(age)
                .gender(gender)
                .build();

        // Pass the request so AuthService can geolocate the user's country from the
        // callback IP (same detection used by password/guest signup).
        LoginResponse login = oauthLoginPort.oauthLogin(info, ClientRequestInfo.from(request, request.getHeader("User-Agent")));

        setAuthCookies(response, login.getTokens().getRefreshToken());
        setMediaCookie(response, login);

        // Full account → land on chats. The "#chats" deep link makes the home gate
        // enter the app (not the marketing page) and the SPA refreshes via the cookie.
        String target = frontendBaseUrl.replaceAll("/+$", "") + "/#chats";
        getRedirectStrategy().sendRedirect(request, response, target);
    }

    /**
     * Sets the HttpOnly {@code refreshToken} cookie and a readable {@code csrf_token} cookie, both
     * with a 30-day lifetime, matching the password-login flow.
     *
     * @param response     the response to attach Set-Cookie headers to
     * @param refreshToken the refresh token to store in the HttpOnly cookie
     */
    private void setAuthCookies(HttpServletResponse response, String refreshToken) {
        long maxAge = 30L * 24 * 60 * 60; // full account: 30 days

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true).secure(cookieSecure).path("/").maxAge(maxAge).sameSite(cookieSameSite).build();

        ResponseCookie csrfCookie = ResponseCookie.from("csrf_token", UUID.randomUUID().toString())
                .httpOnly(false).secure(cookieSecure).path("/").maxAge(maxAge).sameSite(cookieSameSite).build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    /**
     * Mirrors {@code AuthController}: an HttpOnly media-read cookie scoped to the media serve
     * endpoint so {@code <img>} loads can be authorized without an Authorization header.
     */
    private void setMediaCookie(HttpServletResponse response, LoginResponse login) {
        try {
            String username = login.getUser() != null ? login.getUser().getUsername()
                    : tokenProvider.getUsernameFromToken(login.getTokens().getAccessToken());
            if (username == null) return;
            long maxAge = 30L * 24 * 60 * 60;
            String mediaToken = tokenProvider.generateMediaToken(username, maxAge * 1000L);
            if (mediaToken == null) return;
            ResponseCookie mediaCookie = ResponseCookie.from(AuthController.MEDIA_COOKIE, mediaToken)
                    .httpOnly(true).secure(cookieSecure).path(AuthController.MEDIA_COOKIE_PATH)
                    .maxAge(maxAge).sameSite(cookieSameSite).build();
            response.addHeader(HttpHeaders.SET_COOKIE, mediaCookie.toString());
        } catch (Exception e) {
            log.debug("Media cookie not issued on OAuth login: {}", e.getMessage());
        }
    }
}
