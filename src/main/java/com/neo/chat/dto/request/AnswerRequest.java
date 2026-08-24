package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /community-help/{uuid}/answer} (feature #9, COMMUNITY_HELP). A neighbour's
 * reply to a help request. Trimming and moderation are enforced in the service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRequest {

    @NotBlank(message = "Answer must not be blank")
    @Size(max = 500, message = "Answer must be at most 500 characters")
    private String body;
}
