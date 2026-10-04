package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for selecting a preset ("cute") avatar by its manifest id. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelectPresetAvatarRequest {

    @NotBlank(message = "Avatar id is required")
    @Size(max = 64, message = "Avatar id must not exceed 64 characters")
    private String id;
}
