package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.GrantDecision;
import com.chat.talkMe.enums.GrantScope;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;

/** Admin request to grant/deny a feature for a specific user. */
@Data
public class FeatureGrantRequest {

    @NotBlank
    private String key;                    // FeatureKey wire name

    private GrantDecision decision = GrantDecision.ALLOW;
    private GrantScope scope = GrantScope.ADMIN;
    private String cohort;
    private Instant expiresAt;
    private String note;
}
