package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PollVoteRequest {
    @NotBlank(message = "optionId is required")
    private String optionId; // uuid of the PollOption to vote for
}
