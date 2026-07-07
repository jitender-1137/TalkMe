package com.chat.talkMe.security.oauth;

import com.chat.talkMe.dto.OAuthUserInfo;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
 * account (create/link via {@link AuthService#oauthLogin}), sets the same HttpOnly
 * refresh-token + CSRF cookies the password login issues, then redirects to the SPA.
 * The SPA boots, calls {@code /auth/me} → {@code /auth/refresh} (cookie) and lands
 * signed in — no token is ever placed in the URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final GoogleProfileService googleProfileService;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Override
    public void onAuthenticationSuccess(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();

        assert principal != null;
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
                        age = ext.age() == null ? 18 : ext.age();
                        gender = ext.gender() == null ? "MALE" : ext.gender();
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

        // Pass the request so AuthService can geo-locate the user's country from the
        // callback IP (same detection used by password/guest signup).
        LoginResponse login = authService.oauthLogin(info, request.getHeader("User-Agent"), request);

        setAuthCookies(response, login.getTokens().getRefreshToken());

        // Full account → land on chats. The "#chats" deep link makes the home gate
        // enter the app (not the marketing page) and the SPA refreshes via the cookie.
        String target = frontendBaseUrl.replaceAll("/+$", "") + "/#chats";
        getRedirectStrategy().sendRedirect(request, response, target);
    }

    private void setAuthCookies(HttpServletResponse response, String refreshToken) {
        long maxAge = 30L * 24 * 60 * 60; // full account: 30 days

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true).secure(cookieSecure).path("/").maxAge(maxAge).sameSite(cookieSameSite).build();

        ResponseCookie csrfCookie = ResponseCookie.from("csrf_token", UUID.randomUUID().toString())
                .httpOnly(false).secure(cookieSecure).path("/").maxAge(maxAge).sameSite(cookieSameSite).build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }
}
