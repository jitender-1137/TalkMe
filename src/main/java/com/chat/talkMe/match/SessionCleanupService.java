package com.chat.talkMe.match;

public interface SessionCleanupService {
    void cleanupSession(String sessionId, String reason);
}
