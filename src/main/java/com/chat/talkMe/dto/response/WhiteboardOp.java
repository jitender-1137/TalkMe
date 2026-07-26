package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One entry in a chat's ephemeral Shared Whiteboard op-log (feature SHARED_WHITEBOARD).
 *
 * <p>The server is the single source of truth: it stamps {@link #seq} (a per-chat monotonically
 * increasing counter) and {@link #ts} (epoch millis), stores the op JSON in a capped Redis list,
 * and re-broadcasts it on the chat topic. Clients replay the op-log in {@code seq} order to
 * reconstruct the board, and honour {@code clear}/{@code undo} ops when reconciling.
 *
 * <p>Serialized to/from Redis JSON, so it needs a no-arg constructor for Jackson.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhiteboardOp {

    /** Per-chat monotonically increasing sequence number, stamped by the server. */
    private long seq;

    /** "stroke" | "clear" | "undo". */
    private String type;

    /** UUID of the user who authored this op. */
    private String authorUuid;

    /** CSS colour (stroke ops only). */
    private String color;

    /** Stroke width in normalized units (stroke ops only). */
    private double size;

    /** Drawing tool (stroke ops only). */
    private String tool;

    /** Normalized 0..1 [x, y] point pairs (stroke ops only; null for clear/undo). */
    private List<double[]> points;

    /** Server timestamp in epoch millis. */
    private long ts;
}
