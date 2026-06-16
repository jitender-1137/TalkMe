package com.chat.talkMe.match;

import java.util.Map;

public interface ChatRoutingService {
    void relayMessage(String sender, String content);
    void relayGif(String sender, Map<String, Object> media);
    void relayImage(String sender, Map<String, Object> media);
}
