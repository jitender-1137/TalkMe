package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Create a Midnight Event (feature #24). {@code startAt} must be in the future; the room is
 * spun up automatically at that time. {@code maxAttendees} 0 (or omitted) means unlimited.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateEventRequest {

    @NotBlank(message = "Event title is required")
    @Size(max = 140)
    private String title;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "Event start time is required")
    private Instant startAt;

    /** Optional close time; when set and elapsed the event auto-ends. */
    private Instant endAt;

    @Size(max = 60)
    private String category;

    /** Seat cap for GOING RSVPs; 0/omitted = unlimited. */
    @PositiveOrZero
    private int maxAttendees;
}
