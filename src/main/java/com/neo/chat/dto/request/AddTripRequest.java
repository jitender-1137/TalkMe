package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Create-a-trip payload for {@code POST /travel/trips}: a destination (city/place string only —
 * never an exact address) and an inclusive {@code [startDate, endDate]} range, plus an optional
 * private note. Fine-grained rules (start &le; end, dates not in the far past, active-trip cap)
 * are enforced in the service and surface as {@code TM_87x} errors — this DTO carries only
 * coarse presence/length bounds so a malformed payload is rejected early.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddTripRequest {

    @NotNull(message = "Destination is required")
    @Size(max = 120, message = "Destination must not exceed 120 characters")
    private String destination;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    @Size(max = 280, message = "Note must not exceed 280 characters")
    private String note;
}
