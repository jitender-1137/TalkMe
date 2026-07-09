package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for POST /auth/verify-email — carries the one-time verification token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyEmailRequest {

    @NotBlank(message = "Verification token is required")
    private String token;
}
