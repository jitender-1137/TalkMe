package com.neo.chat.event;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.MessageResponse;

/**
 * Port owned by the {@code event} package for the notification-dispatch operations that
 * the WebSocket fan-out needs. Declared here (and implemented by the concrete service in
 * {@code com.neo.chat.service.impl}) so that {@code event} depends only on this local
 * interface, avoiding an {@code event → service} package cycle.
 */
public interface NotificationDispatchPort {

    void onNewMessage(User recipient, String chatUuid, MessageResponse message,
                      String senderName, String senderAvatar);

    /**
     * Recompute the authoritative unread total from the DB, store + broadcast it.
     *
     * <p>Return type matches the concrete impl ({@code int}); callers in {@code event}
     * ignore the value. Declaring {@code void} here would clash with the impl also
     * implementing {@code NotificationDispatchService.recomputeUnread} (which returns
     * {@code int}), since one method cannot override both a {@code void} and an
     * {@code int} signature.</p>
     */
    int recomputeUnread(User actor);
}
