package com.neo.chat.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /rooms/study (STUDY_ROOMS) — create a Pomodoro-driven study room.
 *
 * <p>{@code name} is optional (a blank/null name falls back to a default). {@code focusMinutes}
 * and {@code breakMinutes} are optional; the service applies 25/5 defaults when null and clamps
 * to a sane range.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateStudyRoomRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String subject;

    @Min(1)
    @Max(180)
    private Integer focusMinutes;

    @Min(1)
    @Max(60)
    private Integer breakMinutes;
}
