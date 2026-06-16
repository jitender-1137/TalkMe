package com.chat.talkMe.match;

import java.util.Optional;

public interface WaitingQueueService {
    void enqueue(String username);
    void dequeue(String username);
    Optional<String> pollNext(String excludeUsername);
    boolean isInQueue(String username);
}
