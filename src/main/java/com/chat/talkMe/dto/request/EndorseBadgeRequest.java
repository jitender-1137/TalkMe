package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.BadgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body for endorsing a peer for a cosmetic badge (feature #30).
 */
@Getter
@Setter
public class EndorseBadgeRequest {

    @NotBlank
    private String recipientUuid;

    @NotNull
    private BadgeType badgeType;
}
