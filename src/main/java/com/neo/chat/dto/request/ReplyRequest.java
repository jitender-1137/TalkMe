package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /advice/questions/{uuid}/replies (feature ADVICE_ROOMS). The replier's identity is
 * taken from the authenticated principal and never echoed back — replies are anonymous.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplyRequest {

    @NotBlank
    @Size(max = 4000)
    private String body;

    /**
     * Optional uuid of the reply this one answers (single-level threading); null/blank for a
     * top-level reply. Must belong to the same question.
     */
    private String parentReplyUuid;
}
