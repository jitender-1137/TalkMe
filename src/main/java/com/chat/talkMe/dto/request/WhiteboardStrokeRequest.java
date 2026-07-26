package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * A single drawing stroke a client POSTs to the Shared Whiteboard (feature SHARED_WHITEBOARD).
 *
 * <p>Strokes are ephemeral: the server appends them to a capped Redis op-log for the chat and
 * re-broadcasts them on the chat topic — nothing is persisted to Postgres.
 *
 * <p>{@code points} are normalized 0..1 canvas coordinates ({@code [x, y]} pairs), so the same
 * stroke renders identically on any viewport size. The list is capped at 2000 points; because
 * bean-validation cannot introspect the element type of a {@code List<double[]>}, the cap is
 * ALSO enforced defensively in {@code WhiteboardServiceImpl}.
 */
@Data
public class WhiteboardStrokeRequest {

    @NotBlank
    private String chatUuid;

    /** CSS colour string (e.g. "#ff0055"); echoed back verbatim to peers — bounded to keep the
     * stored/rebroadcast payload small (the field is not a free-text vector). */
    @Size(max = 64, message = "color is too long")
    private String color;

    /** Stroke width in normalized units. */
    private double size;

    /** Drawing tool, e.g. "pen" | "highlighter" | "eraser"; echoed back verbatim. */
    @Size(max = 32, message = "tool is too long")
    private String tool;

    /** Normalized 0..1 [x, y] point pairs making up the stroke path. */
    @Size(max = 2000, message = "A stroke may contain at most 2000 points")
    private List<double[]> points;
}
