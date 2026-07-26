package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for POST /compliments (feature ANON_COMPLIMENTS). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendComplimentRequest {

    /** UUID of the user the compliment is for. */
    @NotBlank
    private String recipientUuid;

    /** The compliment text shown to the recipient. */
    @NotBlank
    @Size(max = 500)
    private String message;
}
