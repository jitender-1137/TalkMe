package com.chat.talkMe.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * A read/delivered status change for a chat, published after the receipt update
 * commits. Carried through the same transactional-outbox machinery as messages so
 * a status tick is never lost on a crash or broker outage.
 *
 * <p>Re-broadcasting is naturally idempotent — re-applying "chat X read by user Y"
 * on a client is a no-op, and the unread recompute reads from the DB — so the status
 * path needs no Redis dedup (unlike message delivery).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusUpdateEvent implements Serializable {

    /** Outbox event type for status changes. */
    public static final String EVENT_TYPE = "message.status";
    /** WebSocket event names (must match what the client listens for). */
    public static final String READ = "messages_read";
    public static final String DELIVERED = "messages_delivered";

    /** Unique outbox key for this status change (a generated UUID). */
    private String eventKey;
    /** Chat the status change applies to. */
    private String chatUuid;
    /** {@link #READ} or {@link #DELIVERED}. */
    private String eventName;
    /** UUID of the user who read/received (the {@code readBy}/{@code deliveredBy} field). */
    private String actorUuid;
    /** DB id of that user, for the unread recompute on a read event (nullable). */
    private Long actorUserId;
}
