package com.chat.talkMe.security.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

/**
 * Stores the in-flight OAuth2 authorization request in a short-lived cookie instead
 * of the HTTP session. The app runs {@code SessionCreationPolicy.STATELESS}, so the
 * default session-backed repository would lose the {@code state}/PKCE values between
 * the redirect to Google and the callback. A cookie survives that round-trip while
 * keeping the server stateless.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int EXPIRE_SECONDS = 180;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = readCookie(request);
        return cookie != null ? deserialize(cookie.getValue()) : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            expireCookie(response);
            return;
        }
        ResponseCookie cookie = baseCookie(serialize(authorizationRequest))
                .maxAge(EXPIRE_SECONDS)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        if (authRequest != null) {
            expireCookie(response);
        }
        return authRequest;
    }

    private Cookie readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c;
        }
        return null;
    }

    private void expireCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("").maxAge(0).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite);
    }

    private String serialize(OAuth2AuthorizationRequest authRequest) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(authRequest);
            oos.flush();
            return Base64.getUrlEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize OAuth2 authorization request", e);
        }
    }

    private OAuth2AuthorizationRequest deserialize(String value) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                return (OAuth2AuthorizationRequest) ois.readObject();
            }
        } catch (Exception e) {
            // Tampered/expired cookie — treat as no in-flight request.
            return null;
        }
    }
}
