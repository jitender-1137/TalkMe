package com.chat.talkMe.match;

import com.chat.talkMe.dto.request.MatchStartRequest;
import com.chat.talkMe.enums.RevealChannel;
import com.chat.talkMe.match.impl.MatchMessageBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP entry point for the anonymous stranger-match feature. Every {@code @MessageMapping}
 * here is an inbound client frame under the {@code /match/*} destination prefix; the
 * authenticated {@link java.security.Principal} identifies the sender and all outbound
 * replies flow back over the user's {@code /queue/match} destination. Each handler is a
 * no-op when the principal (or, where a body is expected, the payload) is null, and
 * delegates to the match services — this controller carries no business logic itself.
 * Handlers cover matchmaking start/exit/new-chat, message/typing/GIF/image relay, the
 * 18+ text-consent handshake, the mutual identity-reveal handshake, and timed-mode actions.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MatchWebSocketController {

    private final MatchmakingService matchmakingService;
    private final ChatRoutingService chatRoutingService;
    private final ImagePermissionService imagePermissionService;
    private final MatchConsentService matchConsentService;
    private final MatchMessageBufferService matchMessageBuffer;
    private final RevealService revealService;
    private final MatchTimerService matchTimerService;

    /**
     * Handles {@code /match/start}: begins searching for a partner. A null body routes to
     * the legacy blind quick-match; a present body routes to preference-aware matching.
     * No-op when the principal is null.
     *
     * @param filters optional match preferences/filters; null ⇒ blind quick-match
     * @param principal the authenticated requester (null ⇒ ignored)
     */
    @MessageMapping("/match/start")
    public void startMatching(
            @Payload(required = false) MatchStartRequest filters,
            Principal principal) {
        if (principal == null) return;
        // No body → legacy blind quick-match; a body → preference-aware match.
        if (filters == null) {
            matchmakingService.startMatching(principal.getName());
        } else {
            matchmakingService.startMatching(principal.getName(), filters);
        }
    }

    /**
     * The client sends this right after (re)subscribing to its match queue on connect.
     * We replay any match messages that were buffered while it had no live socket
     * (backgrounded / suspended / briefly offline). No-op when nothing is buffered.
     *
     * @param principal the authenticated requester (null ⇒ ignored)
     */
    @MessageMapping("/match/resume")
    public void resume(Principal principal) {
        if (principal == null) return;
        matchMessageBuffer.flush(principal.getName());
    }

    /**
     * Handles {@code /match/message}: relays a text message to the current partner. The
     * payload carries {@code content} and a client-generated {@code clientId} echoed back
     * if the message is held (e.g. pending 18+ consent) so the sender's UI can flag it.
     * No-op when the principal or payload is null.
     *
     * @param payload map with {@code content} and {@code clientId} keys
     * @param principal the authenticated sender (null ⇒ ignored)
     */
    @MessageMapping("/match/message")
    public void sendMessage(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String content = (String) payload.get("content");
        // Client-generated message id, echoed back if the message is held so the
        // sender's UI can flag that exact bubble in-place.
        String clientId = (String) payload.get("clientId");
        chatRoutingService.relayMessage(principal.getName(), content, clientId);
    }

    /**
     * Handles {@code /match/typing}: relays an anonymous typing indicator to the partner.
     * No-op when the principal is null.
     *
     * @param typing true if the sender is currently typing
     * @param principal the authenticated sender (null ⇒ ignored)
     */
    @MessageMapping("/match/typing")
    public void typing(@Payload boolean typing, Principal principal) {
        if (principal == null) return;
        chatRoutingService.relayTyping(principal.getName(), typing);
    }

    /**
     * Handles {@code /match/accept-consent}: the peer grants this session's 18+ text
     * consent, unblocking held explicit messages. No-op when the principal is null.
     *
     * @param principal the authenticated accepter (null ⇒ ignored)
     */
    @MessageMapping("/match/accept-consent")
    public void acceptConsent(Principal principal) {
        if (principal == null) return;
        matchConsentService.acceptConsent(principal.getName());
    }

    /**
     * Handles {@code /match/decline-consent}: the peer declines this session's 18+ text
     * consent, incrementing the decline count. No-op when the principal is null.
     *
     * @param principal the authenticated decliner (null ⇒ ignored)
     */
    @MessageMapping("/match/decline-consent")
    public void declineConsent(Principal principal) {
        if (principal == null) return;
        matchConsentService.declineConsent(principal.getName());
    }

    /**
     * Handles {@code /match/gif}: relays a GIF (from the payload's {@code media} map) to
     * the partner. No-op when the principal or payload is null.
     *
     * @param payload map containing a {@code media} descriptor
     * @param principal the authenticated sender (null ⇒ ignored)
     */
    @MessageMapping("/match/gif")
    public void sendGif(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        Map<String, Object> media = (Map<String, Object>) payload.get("media");
        chatRoutingService.relayGif(principal.getName(), media);
    }

    /**
     * Handles {@code /match/request-image}: asks the partner to permit photo exchange.
     * No-op when the principal is null.
     *
     * @param principal the authenticated requester (null ⇒ ignored)
     */
    @MessageMapping("/match/request-image")
    public void requestImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.requestImage(principal.getName());
    }

    /**
     * Handles {@code /match/accept-image}: accepts the pending image request, enabling
     * photo exchange for the session. No-op when the principal is null.
     *
     * @param principal the authenticated approver (null ⇒ ignored)
     */
    @MessageMapping("/match/accept-image")
    public void acceptImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.acceptImageRequest(principal.getName());
    }

    /**
     * Handles {@code /match/decline-image}: declines the pending image request. No-op
     * when the principal is null.
     *
     * @param principal the authenticated decliner (null ⇒ ignored)
     */
    @MessageMapping("/match/decline-image")
    public void declineImage(Principal principal) {
        if (principal == null) return;
        imagePermissionService.declineImageRequest(principal.getName());
    }

    /**
     * Handles {@code /match/send-image}: relays a photo (from the payload's {@code media}
     * map) to the partner; the routing layer rejects it unless image permission was
     * granted. No-op when the principal or payload is null.
     *
     * @param payload map containing a {@code media} descriptor
     * @param principal the authenticated sender (null ⇒ ignored)
     */
    @MessageMapping("/match/send-image")
    public void sendImage(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        Map<String, Object> media = (Map<String, Object>) payload.get("media");
        chatRoutingService.relayImage(principal.getName(), media);
    }

    // ── Anonymous Mask reveal handshake (features #6/#15/#16) ──
    /**
     * Handles {@code /match/reveal-request}: offers to reveal the payload's {@code channel}
     * (PROFILE/VOICE/PHOTO) and asks the peer; the reveal only completes when the peer
     * also grants. No-op when the principal or payload is null.
     *
     * @param payload map containing a {@code channel} name
     * @param principal the authenticated requester (null ⇒ ignored)
     */
    @MessageMapping("/match/reveal-request")
    public void revealRequest(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        revealService.requestReveal(principal.getName(), parseChannel(payload));
    }

    /**
     * Handles {@code /match/reveal-accept}: grants the payload's {@code channel} reveal;
     * when both sides have granted, identities/payloads are exchanged. No-op when the
     * principal or payload is null.
     *
     * @param payload map containing a {@code channel} name
     * @param principal the authenticated accepter (null ⇒ ignored)
     */
    @MessageMapping("/match/reveal-accept")
    public void revealAccept(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        revealService.acceptReveal(principal.getName(), parseChannel(payload));
    }

    /**
     * Handles {@code /match/reveal-decline}: declines the payload's {@code channel} reveal.
     * No-op when the principal or payload is null.
     *
     * @param payload map containing a {@code channel} name
     * @param principal the authenticated decliner (null ⇒ ignored)
     */
    @MessageMapping("/match/reveal-decline")
    public void revealDecline(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        revealService.declineReveal(principal.getName(), parseChannel(payload));
    }

    /**
     * Parses the {@code channel} value from a reveal payload into a {@link RevealChannel},
     * trimming and upper-casing it.
     *
     * @param payload the inbound reveal payload
     * @return the resolved reveal channel
     * @throws java.lang.IllegalArgumentException if the value is not a valid channel name
     */
    private RevealChannel parseChannel(Map<String, Object> payload) {
        Object c = payload.get("channel");
        return RevealChannel.valueOf(String.valueOf(c).trim().toUpperCase());
    }

    // ── Coffee/Chemistry post-timer actions (features #7/#14) ──
    /**
     * Handles {@code /match/timed-action}: dispatches a Coffee/Chemistry post-timer action
     * from the payload's {@code action}: END exits, REMATCH starts a new chat,
     * EXCHANGE_PROFILES/ADD_FRIEND trigger a consent-gated PROFILE reveal, and CONTINUE
     * requests extending the timer; unknown actions are ignored. No-op when the principal
     * or payload is null.
     *
     * @param payload map containing an {@code action} name
     * @param principal the authenticated user (null ⇒ ignored)
     */
    @MessageMapping("/match/timed-action")
    public void timedAction(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String action = String.valueOf(payload.get("action")).trim().toUpperCase();
        String name = principal.getName();
        switch (action) {
            case "END" -> matchmakingService.handleExit(name);
            case "REMATCH" -> matchmakingService.handleNewChat(name);
            // Exchanging profiles / adding a friend is a consent-gated PROFILE reveal.
            case "EXCHANGE_PROFILES", "ADD_FRIEND" -> revealService.requestReveal(name, RevealChannel.PROFILE);
            case "CONTINUE" -> matchTimerService.continueRequest(name);
            default -> { /* ignore unknown */ }
        }
    }

    /**
     * Handles {@code /match/exit}: leaves the current match or cancels an in-progress
     * search. No-op when the principal is null.
     *
     * @param principal the authenticated user (null ⇒ ignored)
     */
    @MessageMapping("/match/exit")
    public void exitChat(Principal principal) {
        if (principal == null) return;
        matchmakingService.handleExit(principal.getName());
    }

    /**
     * Handles {@code /match/new-chat}: ends the current match and re-enters matchmaking
     * for a fresh partner. No-op when the principal is null.
     *
     * @param principal the authenticated user (null ⇒ ignored)
     */
    @MessageMapping("/match/new-chat")
    public void newChat(Principal principal) {
        if (principal == null) return;
        matchmakingService.handleNewChat(principal.getName());
    }
}
