package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.ConsentType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConsentAcceptRequest {

    @NotNull
    private ConsentType type;

    /** The version the client is accepting. When null/stale, the server uses the current required version. */
    private String version;
}
