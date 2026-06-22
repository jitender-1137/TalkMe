package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.ChatRoutingService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoutingServiceImpl implements ChatRoutingService {

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void relayMessage(String sender, String content) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

        String recipient = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();

        MatchServerEvent event = MatchServerEvent.builder()
                .event("MESSAGE_RECEIVED")
                .payload(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "content", content,                        "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed text message from {} to {}", sender, recipient);
    }

    @Override
    public void relayGif(String sender, Map<String, Object> media) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

        String recipient = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();

        MatchServerEvent event = MatchServerEvent.builder()
                .event("GIF_RECEIVED")
                .payload(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "media", media,                        "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed GIF message from {} to {}", sender, recipient);
    }

    @Override
    public void relayImage(String sender, Map<String, Object> media) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

        if (!session.isImagePermissionStatus()) {
            throw new IllegalStateException("Image exchange is not approved for this session");
        }

        String recipient = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();

        MatchServerEvent event = MatchServerEvent.builder()
                .event("IMAGE_RECEIVED")
                .payload(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "media", media,                        "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed Image message from {} to {}", sender, recipient);
    }
}
