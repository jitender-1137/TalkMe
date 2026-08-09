package com.chat.talkMe.match;

import java.util.Map;

/**
 * Relays in-session messages between the two anonymous match participants over WebSocket. Forwards
 * text, GIFs, images, and typing signals to the peer while preserving the anonymity invariant —
 * only the message payload is routed, never the sender's identity (username, name, avatar, UUID).
 */
public interface ChatRoutingService {
    void relayMessage(String sender, String content, String clientId);

    void relayGif(String sender, Map<String, Object> media);

    void relayImage(String sender, Map<String, Object> media);

    /**
     * Forward an anonymous typing signal to the peer (no identity leaked).
     */
    void relayTyping(String sender, boolean typing);
}
