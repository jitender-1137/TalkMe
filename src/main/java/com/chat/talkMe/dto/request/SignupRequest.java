package com.chat.talkMe.dto.request;

import com.chat.talkMe.validator.ValidAge;
import com.chat.talkMe.validator.ValidGender;
import com.chat.talkMe.validator.ValidPassword;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // tolerate client-only fields (e.g. confirmPassword)
public class SignupRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Name is required")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    private String email;

    @NotBlank(message = "Password is required")
    @ValidPassword
    private String password;

    @ValidAge
    private int age;

    @ValidGender
    private String gender;

    /** Cloudflare Turnstile token (verified server-side). */
    private String captchaToken;

    /** Honeypot — must stay empty; bots tend to fill every field. */
    private String website;
}
