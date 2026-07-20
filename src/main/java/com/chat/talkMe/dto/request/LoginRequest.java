package com.chat.talkMe.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // tolerate client-only fields (e.g. rememberMe, isGuest)
public class LoginRequest {
    
    @NotBlank(message = "Username or email is required")
    private String email; // Note: mapped as email, but holds either username or email in unified logins
    
    @NotBlank(message = "Password is required")
    private String password;

    /** Cloudflare Turnstile token (verified server-side). */
    private String captchaToken;

    /** Honeypot — must stay empty; bots tend to fill every field. */
    private String website;
}
