package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;

/**
 * Routes a newly-created message to a recipient: always bumps + broadcasts the
 * server-driven unread count over WebSocket, and additionally sends a Web Push
 * notification when the recipient's installation type is PWA / IOS_HOME.
 */
public interface NotificationDispatchService {

    void onNewMessage(User recipient, String chatUuid, MessageResponse message,
                      String senderName, String senderAvatar);

    /**
     * Web Push for an ephemeral, non-persisted message (stranger match / lobby chat).
     * Unlike {@link #onNewMessage} this does NOT touch the unread count — those chats
     * have no server-side history. Callers should invoke this ONLY when the recipient
     * has no live websocket session (i.e. their app is backgrounded/suspended), so a
     * foreground user is never double-notified. No-op when push is disabled.
     *
     * @param url app-relative deep link the notification opens (e.g. "/#match/quick").
     */
    void onEphemeralMessage(Long recipientUserId, String title, String body, String url);

    /** Recompute the authoritative unread total from the DB, store + broadcast it. */
    int recomputeUnread(User user);
}
