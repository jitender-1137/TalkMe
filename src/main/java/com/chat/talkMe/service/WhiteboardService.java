package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.WhiteboardStrokeRequest;
import com.chat.talkMe.dto.response.WhiteboardOp;

import java.util.List;

/**
 * Real-time collaborative drawing inside a 1:1 chat (feature SHARED_WHITEBOARD).
 *
 * <p>Strokes are EPHEMERAL: the server appends each op to a capped Redis op-log for the chat
 * and re-broadcasts it on the existing chat topic. Nothing is written to Postgres. Every method
 * IDOR-guards the caller (must be a member of the chat) before touching Redis.
 */
public interface WhiteboardService {

    /** Replay the current op-log (in seq order) for the chat. */
    List<WhiteboardOp> getBoard(User me, String chatUuid);

    /** Append a stroke op, broadcast it live, and return the server-stamped op. */
    WhiteboardOp addStroke(User me, WhiteboardStrokeRequest req);

    /** Wipe the board: drop the stored op-log, push a clear marker, broadcast it. */
    void clear(User me, String chatUuid);

    /** Append an undo op (clients remove the author's last stroke), broadcast it, return the op. */
    WhiteboardOp undo(User me, String chatUuid);
}
