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

    @MessageMapping("/match/start")
    public void startMatching(Principal principal) {
        if (principal == null) return;
        matchmakingService.startMatching(principal.getName());
    }

    @MessageMapping("/match/message")
    public void sendMessage(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String content = (String) payload.get("content");
        chatRoutingService.relayMessage(principal.getName(), content);
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
