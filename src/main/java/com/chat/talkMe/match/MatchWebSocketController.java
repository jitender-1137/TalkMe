package com.chat.talkMe.match;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import java.security.Principal;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MatchWebSocketController {

    private final MatchmakingService matchmakingService;
    private final ChatRoutingService chatRoutingService;
    private final ImagePermissionService imagePermissionService;
    private final MatchConsentService matchConsentService;
    private final com.chat.talkMe.match.impl.MatchMessageBufferService matchMessageBuffer;

    @MessageMapping("/match/start")
    public void startMatching(Principal principal) {
        if (principal == null) return;
        matchmakingService.startMatching(principal.getName());
    }

    /**
     * The client sends this right after (re)subscribing to its match queue on connect.
     * We replay any match messages that were buffered while it had no live socket
     * (backgrounded / suspended / briefly offline). No-op when nothing is buffered.
     */
    @MessageMapping("/match/resume")
    public void resume(Principal principal) {
        if (principal == null) return;
        matchMessageBuffer.flush(principal.getName());
    }

    @MessageMapping("/match/message")
    public void sendMessage(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String content = (String) payload.get("content");
        // Client-generated message id, echoed back if the message is held so the
        // sender's UI can flag that exact bubble in-place.
        String clientId = (String) payload.get("clientId");
        chatRoutingService.relayMessage(principal.getName(), content, clientId);
    }

    @MessageMapping("/match/typing")
    public void typing(@Payload boolean typing, Principal principal) {
        if (principal == null) return;
        chatRoutingService.relayTyping(principal.getName(), typing);
    }

    @MessageMapping("/match/accept-consent")
    public void acceptConsent(Principal principal) {
        if (principal == null) return;
        matchConsentService.acceptConsent(principal.getName());
    }

    @MessageMapping("/match/decline-consent")
    public void declineConsent(Principal principal) {
        if (principal == null) return;
        matchConsentService.declineConsent(principal.getName());
    }

    @MessageMapping("/match/gif")
    public void sendGif(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        Map<String, Object> media = (Map<String, Object>) payload.get("media");
        chatRoutingService.relayGif(principal.getName(), media);
    }

    @MessageMapping("/match/request-image")
    public void requestImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.requestImage(principal.getName());
    }

    @MessageMapping("/match/accept-image")
    public void acceptImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.acceptImageRequest(principal.getName());
    }

    @MessageMapping("/match/decline-image")
    public void declineImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.declineImageRequest(principal.getName());
    }

    @MessageMapping("/match/send-image")
    public void sendImage(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        Map<String, Object> media = (Map<String, Object>) payload.get("media");
        chatRoutingService.relayImage(principal.getName(), media);
    }

    @MessageMapping("/match/exit")
    public void exitChat(Principal principal) {
        if (principal == null) return;
        matchmakingService.handleExit(principal.getName());
    }

    @MessageMapping("/match/new-chat")
    public void newChat(Principal principal) {
        if (principal == null) return;
        matchmakingService.handleNewChat(principal.getName());
    }
}
