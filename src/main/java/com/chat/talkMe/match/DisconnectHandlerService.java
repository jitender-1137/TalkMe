package com.chat.talkMe.match;

public interface DisconnectHandlerService {

    /** Immediate teardown: dequeue, destroy any active session, notify the peer. */
    void handleDisconnect(String username);

    /**
     * A socket dropped mid-matchmaking. Instead of tearing down now, hold the state for
     * a grace window so a backgrounded/blipped client can reconnect and resume. If the
     * user is in an active session the peer is told STRANGER_RECONNECTING; the queue
     * spot is preserved for a searching user. {@link #reapExpiredDisconnects()} runs the
     * real teardown if the grace expires with no reconnect.
     */
    void scheduleDisconnect(String username);

    /**
     * The user reconnected within the grace window — abort the pending teardown and, if
     * matched, tell the peer STRANGER_RECONNECTED so the chat resumes seamlessly. No-op
     * when nothing is pending.
     */
    void cancelDisconnect(String username);

    /**
     * Tear down every match whose reconnect grace has elapsed with the user still
     * offline. Returns the number reaped. Invoked on a fixed cadence by a scheduler.
     */
    int reapExpiredDisconnects();
}
