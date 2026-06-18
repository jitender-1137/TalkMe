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

    /** Recompute the authoritative unread total from the DB, store + broadcast it. */
    int recomputeUnread(User user);
}
