package com.chat.talkMe.security;

import io.jsonwebtoken.Claims;
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

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long jwtExpirationInMs;

    public JwtTokenProvider(
            @Value("${security.jwt.secret-key}") String secret,
            @Value("${security.jwt.access-token-expiration-ms}") long jwtExpirationInMs) {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.jwtExpirationInMs = jwtExpirationInMs;
    }

    public String generateToken(Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        return generateToken(userDetails.getUsername(), userDetails.isGuest());
    }

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

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

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
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
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
