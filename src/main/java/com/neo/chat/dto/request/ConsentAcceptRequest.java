package com.neo.chat.dto.request;

import com.neo.chat.enums.ConsentType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConsentAcceptRequest {

    @NotNull
    private ConsentType type;

    /**
     * The version the client is accepting. When null/stale, the server uses the current required version.
     */
    private String version;
}
