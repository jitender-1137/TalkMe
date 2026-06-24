package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;

/**
 * Strategy for re-delivering one type of outbox event. Each event type
 * (message.send, message.status, …) registers one handler; {@link OutboxDispatcher}
 * routes a pending row to the matching handler during catch-up re-drive.
 *
 * <p>Implementations only need to broadcast — claiming the row and marking it
 * published is the dispatcher's job. Broadcasts must be idempotent (the same row
 * may be re-driven if a previous attempt half-completed).
 */
public interface OutboxDeliveryHandler {

    /** The {@link OutboxEvent#getEventType()} value this handler is responsible for. */
    String eventType();

    /** Re-deliver the event carried by this row. May throw to signal a retryable failure. */
    void broadcast(OutboxEvent row) throws Exception;
}
