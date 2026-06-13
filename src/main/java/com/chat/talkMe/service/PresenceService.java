package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.websocket.PresenceNotification;

public interface PresenceService {
    void setStatus(User user, PresenceStatus status);
    PresenceStatus getStatus(User user);
    void toggleGhostMode(User user, boolean enabled);
    void toggleInvisibleMode(User user, boolean enabled);
    void resetPresence(User user);
    boolean isUserOnline(User user);
    com.chat.talkMe.domain.UserPresence getUserPresence(User user);
}
