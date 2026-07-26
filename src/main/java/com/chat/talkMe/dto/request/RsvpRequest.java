package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Set/change the caller's RSVP to an event (feature #24). {@code status} is a
 * {@link com.chat.talkMe.enums.RsvpStatus} name: GOING | INTERESTED | DECLINED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RsvpRequest {

    @NotBlank(message = "RSVP status is required")
    private String status;
}
