package com.chat.talkMe.match;

import java.util.Map;

public interface ChatRoutingService {
    void relayMessage(String sender, String content, String clientId);
    void relayGif(String sender, Map<String, Object> media);
    void relayImage(String sender, Map<String, Object> media);
    /** Forward an anonymous typing signal to the peer (no identity leaked). */
    void relayTyping(String sender, boolean typing);
}
