package com.chat.talkMe.dto.request;

import com.chat.talkMe.validator.ValidAge;
import com.chat.talkMe.validator.ValidGender;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class GuestLoginRequest {

    @NotBlank(message = "Guest name is required")
    private String name;

    @ValidAge
    private Integer age;

    @ValidGender
    private String gender;
}
