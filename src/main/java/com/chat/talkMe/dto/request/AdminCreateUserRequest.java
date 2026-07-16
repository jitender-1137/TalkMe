package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** SuperAdmin create-user payload. Creates a verified, non-guest ROLE_USER account. */
@Data
public class AdminCreateUserRequest {
    @NotBlank
    private String name;
    @NotBlank @Email
    private String email;
    @NotBlank @Size(min = 3, max = 50)
    private String username;
    @NotBlank @Size(min = 6, max = 100)
    private String password;
    private Integer age;
    private String gender;
    private String country;
}
