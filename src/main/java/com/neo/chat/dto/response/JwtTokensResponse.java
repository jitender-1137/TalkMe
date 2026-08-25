package com.neo.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtTokensResponse {
    private String accessToken;
    private long expiresIn;

    /**
     * Refresh token is intentionally excluded from JSON serialization.
     * It is only used internally by the controller to set the HttpOnly
     * cookie via Set-Cookie header. It must never be sent in the response body.
     */
    @JsonIgnore
    private String refreshToken;

    /**
     * The authenticated user, populated ONLY on the {@code /auth/refresh} path so the
     * client can restore a session in a SINGLE round trip on a page reload (the
     * in-memory access token is gone after a reload, so the old flow was
     * /auth/me → 401 → /auth/refresh → /auth/me = 3 hops). It carries the same payload
     * as {@code /auth/me}. Omitted from JSON when null (via NON_NULL), so the login /
     * signup / guest / OAuth token payloads — which don't set it — are byte-for-byte
     * unchanged. Safe to return here: /auth/refresh already authenticates via the
     * HttpOnly refresh cookie, so this is the caller's own profile, and the response is
     * application/json (excluded from gzip → no BREACH surface).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuthUserResponse user;
}
