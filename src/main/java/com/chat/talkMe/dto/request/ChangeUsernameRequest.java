package com.chat.talkMe.dto.request;

import com.chat.talkMe.validator.ValidUsername;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for changing the current user's username (PATCH /users/me/username). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangeUsernameRequest {
    @NotBlank(message = "Username is required")
    @ValidUsername
    private String username;
}
