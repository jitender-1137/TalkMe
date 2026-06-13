package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDeviceRequest {

    @NotBlank(message = "Device token is required")
    @Size(max = 255, message = "Device token must not exceed 255 characters")
    private String deviceToken;

    @Size(max = 50, message = "Device type must not exceed 50 characters")
    private String deviceType;

    @Size(max = 50, message = "OS version must not exceed 50 characters")
    private String osVersion;
}
