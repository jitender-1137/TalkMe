package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Bulk accept/reject of pending friend requests in a SINGLE call. The old "Respond All"
 * UI fired one HTTP request per pending request, which tripped the per-user rate limiter;
 * this lets the client send the whole selection at once and the server process it in one
 * transaction.
 */
@Data
public class BulkFriendRequestActionRequest {

    /** UUIDs of the pending friend requests to act on (the caller must be their receiver). */
    @NotEmpty(message = "At least one request id is required")
    private List<String> requestIds;

    /** Whether to accept or reject the selected requests. */
    @NotNull(message = "action is required")
    private Action action;

    public enum Action {
        ACCEPT,
        REJECT
    }
}
