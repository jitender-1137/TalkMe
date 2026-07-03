package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.ImagePermissionService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePermissionServiceImpl implements ImagePermissionService {

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.chat.talkMe.service.BotRegistry botRegistry;

    @Override
    public void requestImage(String requester) {
        MatchSession session = sessionService.getSessionByUser(requester)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + requester));

        String recipient = session.getUserA().equals(requester) ? session.getUserB() : session.getUserA();

        // Bots are text-only — they never share photos. Auto-decline so the requester
        // gets an immediate, clean response instead of waiting on a peer that can't answer.
        if (botRegistry.isBot(recipient)) {
            messagingTemplate.convertAndSendToUser(requester, "/queue/match",
                    MatchServerEvent.builder().event("IMAGE_REQUEST_DECLINED").payload(Map.of()).build());
            log.info("Auto-declined IMAGE_REQUEST from {} (peer is a bot)", requester);
            return;
        }

        MatchServerEvent event = MatchServerEvent.builder()
                .event("IMAGE_REQUEST_RECEIVED")
                .payload(Map.of()) // anonymous — event signal only, no requester identity
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Forwarded IMAGE_REQUEST from {} to {}", requester, recipient);
    }

    @Override
    public void acceptImageRequest(String approver) {
        MatchSession session = sessionService.getSessionByUser(approver)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + approver));

        // Enable image permission for this session
        sessionService.grantImagePermission(session.getId());

        // Notify both users
        MatchServerEvent event = MatchServerEvent.builder()
                .event("IMAGE_REQUEST_ACCEPTED")
                .payload(Map.of()) // anonymous — event signal only, no approver identity
                .build();

        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", event);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", event);
        log.info("Image request accepted for session {}", session.getId());
    }

    @Override
    public void declineImageRequest(String decliner) {
        MatchSession session = sessionService.getSessionByUser(decliner)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + decliner));

        // Notify both users
        MatchServerEvent event = MatchServerEvent.builder()
                .event("IMAGE_REQUEST_DECLINED")
                .payload(Map.of()) // anonymous — event signal only, no decliner identity
                .build();

        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", event);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", event);
        log.info("Image request declined for session {}", session.getId());
    }
}
