package com.chat.talkMe.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Issues and verifies the app's HMAC-SHA256 (HS256) JWTs. Handles both the short-lived
 * access tokens used as Bearer credentials over HTTP/WebSocket and the narrowly-scoped,
 * long-lived Web Push delivery-ack tokens. All tokens are signed with the same secret
 * key, so {@link #validateToken} additionally distinguishes them by {@code purpose} claim
 * to prevent a delivery token from being replayed as an access credential.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long jwtExpirationInMs;

    /**
     * Builds the provider, decoding the Base64 signing secret into an HS256 key.
     *
     * @param secret            Base64-encoded HMAC signing secret from configuration
     * @param jwtExpirationInMs access-token lifetime in milliseconds
     */
    public JwtTokenProvider(
            @Value("${security.jwt.secret-key}") String secret,
            @Value("${security.jwt.access-token-expiration-ms}") long jwtExpirationInMs) {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.jwtExpirationInMs = jwtExpirationInMs;
    }

    /**
     * Generates an access token for the principal held by the given authentication.
     *
     * @param authentication authenticated token whose principal is a {@link CustomUserDetails}
     * @return a signed access token carrying the username as subject and the guest flag
     * @throws java.lang.ClassCastException if the principal is not a {@link CustomUserDetails}
     */
    public String generateToken(Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        return generateToken(userDetails.getUsername(), userDetails.isGuest());
    }

    /**
     * Generates a signed access token with the username as subject and an {@code isGuest} claim,
     * expiring after the configured access-token lifetime.
     *
     * @param username subject (username) to embed
     * @param isGuest  whether the account is a guest account
     * @return the compact, signed JWT string
     */
    public String generateToken(String username, boolean isGuest) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        Map<String, Object> claims = new HashMap<>();
        claims.put("isGuest", isGuest);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies the token's signature and returns its subject (username).
     *
     * @param token a signed JWT
     * @return the subject claim (username)
     * @throws io.jsonwebtoken.JwtException if the token is malformed, expired, or its signature
     *                                      fails verification
     */
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * Validates a token for use as an access credential: verifies the signature and rejects
     * delivery-ack tokens (those carrying the push-delivery {@code purpose} claim) so they cannot
     * be replayed as Bearer credentials. Expiry is logged at DEBUG (routine); other failures at WARN.
     *
     * @param token the JWT to validate
     * @return {@code true} if the token is a valid, non-expired access token; {@code false} otherwise
     *         (expired, malformed, bad signature, or a delivery-ack token)
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            // SECURITY: the access-token path must reject narrowly-scoped tokens that
            // happen to be signed with the same key (e.g. push-delivery ack tokens).
            // Without this, such a token — which is embedded in push payloads and
            // lives for days — could be replayed as a Bearer credential and grant
            // full account access over HTTP and WebSocket.
            if (DELIVERY_PURPOSE.equals(claims.get("purpose", String.class))) {
                return false;
            }
            return true;
        } catch (ExpiredJwtException ex) {
            // An expired access token is a NORMAL, expected condition — the client
            // refreshes via its refresh-token cookie. Log at DEBUG so routine expiry
            // (e.g. a long-open tab whose 15-min token lapsed) doesn't flood ERROR logs.
            log.debug("JWT expired: {}", ex.getMessage());
        } catch (Exception ex) {
            // Malformed / bad-signature / unsupported tokens are worth a WARN — they can
            // indicate tampering or a client bug — but are still not a server ERROR.
            log.warn("JWT validation error: {}", ex.getMessage());
        }
        return false;
    }

    // ── Web Push delivery-ack tokens ────────────────────────────────────────────
    // A short-lived, signed, narrowly-scoped token embedded in a push payload. The
    // service worker posts it back when the push is RECEIVED on the device, so the
    // server can mark the message delivered and notify the sender — without the SW
    // needing the user's access token (which it can't read). The token only grants
    // "mark THIS chat delivered for THIS user", and nothing else.
    private static final String DELIVERY_PURPOSE = "push-delivery";
    private static final long DELIVERY_TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    /**
     * Generates a narrowly-scoped Web Push delivery-ack token, valid for 7 days, that only
     * authorizes marking the given chat delivered for the given user.
     *
     * @param username subject (username) the token is issued for
     * @param chatUuid chat the delivery ack applies to (embedded as a claim)
     * @return the compact, signed delivery-ack JWT string
     */
    public String generateDeliveryToken(String username, String chatUuid) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put("purpose", DELIVERY_PURPOSE);
        claims.put("chatUuid", chatUuid);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + DELIVERY_TOKEN_TTL_MS))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verify a delivery-ack token and return its claims, or {@code null} if it is
     * invalid, expired, or not a delivery token.
     */
    public Claims parseDeliveryToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!DELIVERY_PURPOSE.equals(claims.get("purpose", String.class))) {
                return null;
            }
            return claims;
        } catch (Exception ex) {
            log.warn("Delivery token validation error: {}", ex.getMessage());
            return null;
        }
    }
}
