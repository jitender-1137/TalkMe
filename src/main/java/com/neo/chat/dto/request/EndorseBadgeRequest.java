package com.neo.chat.dto.request;

import com.neo.chat.enums.BadgeType;
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
