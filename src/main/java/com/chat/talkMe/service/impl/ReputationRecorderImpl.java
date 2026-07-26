package com.chat.talkMe.service.impl;

import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.event.ReputationSignal;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ReputationRecorderImpl implements ReputationRecorder {

    private final ApplicationEventPublisher publisher;

    @Override
    public void record(Long userId, ReputationEventType type, String sourceRef) {
        if (userId == null || type == null) return;
        publisher.publishEvent(new ReputationSignal(userId, type, sourceRef, Instant.now()));
    }
}
