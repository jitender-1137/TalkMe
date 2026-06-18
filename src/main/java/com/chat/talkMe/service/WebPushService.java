package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SavePushSubscriptionRequest;

public interface WebPushService {

    /** Create or update a push subscription for the given user. */
    void saveSubscription(User user, SavePushSubscriptionRequest request);

    /** Remove a subscription by endpoint (e.g. on logout / unsubscribe). */
    void removeSubscription(String endpoint);

    /**
     * Send a Web Push payload to every subscription of a user. Runs asynchronously
     * so it never blocks the message transaction. Prunes endpoints reported gone
     * (HTTP 404/410).
     */
    void sendToUser(Long userId, String payloadJson);
}
