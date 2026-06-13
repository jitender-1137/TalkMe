package com.chat.talkMe.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
}
