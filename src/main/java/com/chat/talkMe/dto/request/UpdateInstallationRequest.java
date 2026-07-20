package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.InstallationType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reports how the user is currently accessing the app (BROWSER / PWA / IOS_HOME). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateInstallationRequest {

    @NotNull(message = "installationType is required")
    private InstallationType installationType;
}
