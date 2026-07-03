package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.match.ChatRoutingService;
import com.chat.talkMe.match.MatchConsentService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoutingServiceImpl implements ChatRoutingService {

    /** Same Redis set the presence listener maintains: non-empty ⇒ a live socket. */
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /** Deep link the match notification opens. */
    private static final String MATCH_DEEP_LINK = "/#match/quick";

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;
    private final MatchConsentService matchConsentService;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final NotificationDispatchService notificationDispatchService;
    private final MatchMessageBufferService matchMessageBuffer;
    private final com.chat.talkMe.service.BotRegistry botRegistry;
    private final BotMatchService botMatchService;

    @Override
    public void relayMessage(String sender, String content, String clientId) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

        // If the peer is an AI bot, generate & relay its reply instead of routing to a
        // (non-existent) peer socket. The bot persona is a consenting adult, so the
        // explicit-consent hold below is skipped for bot sessions.
        String peer = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();
        if (botRegistry.isBot(peer)) {
            botMatchService.onHumanMessage(session, sender, peer, content);
            return;
        }

        // Explicit text requires per-session 18+ consent. Until the peer has GRANTED,
        // the message is held (never relayed): we auto-ask the peer and flag the
        // sender's own message in-place — no spammy toasts.
        if (moderationService.moderateText(content).isExplicit()
                && session.getConsentStatus() != ConsentStatus.GRANTED) {
            matchConsentService.handleHeldExplicit(sender, clientId, session);
            return;
        }

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
        onRelayed(recipient, event, content);
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
        onRelayed(recipient, event, "🎬 GIF");
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
        onRelayed(recipient, event, "📷 Photo");
    }

    @Override
    public void relayTyping(String sender, boolean typing) {
        sessionService.getSessionByUser(sender).ifPresent(session -> {
            String recipient = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();
            // Anonymous typing signal — only the boolean travels, never the username.
            MatchServerEvent event = MatchServerEvent.builder()
                    .event("STRANGER_TYPING")
                    .payload(Map.of("isTyping", typing))
                    .build();
            messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        });
    }

    /**
     * Handle a relayed event for a recipient that may be backgrounded. If they have a
     * live socket the STOMP frame above was delivered in-app — nothing more to do. If
     * not, that frame was dropped, so we (1) BUFFER the event for replay when they
     * reconnect (match messages are otherwise ephemeral and would be lost from the
     * thread) and (2) fire an anonymous Web Push so they know to come back.
     */
    private void onRelayed(String recipient, MatchServerEvent event, String pushBody) {
        try {
            Long live = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + recipient);
            if (live != null && live > 0) {
                return; // recipient is connected — in-app delivery already happened
            }
            // No live socket: the relayed frame never landed. Hold it for replay…
            matchMessageBuffer.buffer(recipient, event);
            // …and notify (anonymous — no partner identity, per the match privacy rule).
            userRepository.findByUsername(recipient).ifPresent(user ->
                    notificationDispatchService.onEphemeralMessage(
                            user.getId(), "New message", pushBody, MATCH_DEEP_LINK));
        } catch (Exception e) {
            // Best-effort — buffering/push must never break message relay.
            log.warn("[Match] background delivery handling failed for {}", recipient, e);
        }
    }
}
