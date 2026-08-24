package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * A single trip as seen by its owner (Travel Companion). Includes the private {@code note} —
 * this projection is only ever returned to the trip's owner. When a trip is exposed to another
 * traveler it is carried inside {@link TravelCompanionResponse} without the note.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripResponse {
    private String uuid;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;
    private String status;
}
