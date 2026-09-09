package com.neo.chat.security.oauth;

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

    private final boolean cookieSecure;
    private final String cookieSameSite;

    /**
     * Constructor-injects the cookie attributes.
     *
     * @param cookieSecure   {@code app.cookie.secure} (default false)
     * @param cookieSameSite {@code app.cookie.same-site} (default Lax)
     */
    public HttpCookieOAuth2AuthorizationRequestRepository(
            @Value("${app.cookie.secure:false}") boolean cookieSecure,
            @Value("${app.cookie.same-site:Lax}") String cookieSameSite) {
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    /**
     * Loads the in-flight authorization request from the cookie, if present.
     *
     * @param request the current HTTP request
     * @return the deserialized authorization request, or {@code null} if no valid cookie exists
     */
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = readCookie(request);
        return cookie != null ? deserialize(cookie.getValue()) : null;
    }

    /**
     * Persists the authorization request in a short-lived cookie, or expires the cookie when the
     * request is {@code null}.
     *
     * @param authorizationRequest the request to store, or {@code null} to clear
     * @param request              the current HTTP request
     * @param response             the response to attach the Set-Cookie header to
     */
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

    /**
     * Loads then expires the authorization-request cookie, returning the request it held.
     *
     * @param request  the current HTTP request
     * @param response the response used to expire the cookie
     * @return the previously-stored authorization request, or {@code null} if none
     */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        if (authRequest != null) {
            expireCookie(response);
        }
        return authRequest;
    }

    /**
     * Finds the authorization-request cookie on the request.
     *
     * @param request the current HTTP request
     * @return the cookie, or {@code null} if not present
     */
    private Cookie readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c;
        }
        return null;
    }

    /**
     * Emits a Set-Cookie header that immediately expires the authorization-request cookie.
     *
     * @param response the response to attach the header to
     */
    private void expireCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("").maxAge(0).build().toString());
    }

    /**
     * Builds the shared cookie template (path {@code /}, HttpOnly, configured secure/SameSite).
     *
     * @param value the cookie value to set
     * @return a cookie builder pre-populated with the common attributes
     */
    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite);
    }

    /**
     * Java-serializes the authorization request and Base64-URL-encodes it for cookie storage.
     *
     * @param authRequest the authorization request to encode
     * @return the Base64-URL-encoded serialized form
     * @throws java.lang.IllegalStateException if serialization fails
     */
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

    /**
     * Decodes and deserializes a cookie value back into an authorization request.
     *
     * @param value the Base64-URL-encoded serialized authorization request
     * @return the deserialized request, or {@code null} if the value is tampered/expired/unreadable
     */
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
