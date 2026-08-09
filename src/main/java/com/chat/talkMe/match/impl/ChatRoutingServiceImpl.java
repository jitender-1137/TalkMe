package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.match.ChatRoutingService;
import com.chat.talkMe.match.MatchConsentService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Relays ephemeral stranger-match content (text, GIFs, images, typing) between the two
 * peers of a session over STOMP, always resolving the recipient as "the other user" so
 * identities are never leaked. Enforces per-session 18+ text consent (explicit messages
 * are held until granted) and image permission (photos rejected until approved), and for
 * a backgrounded recipient with no live socket it buffers the frame for replay and fires
 * an anonymous push.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoutingServiceImpl implements ChatRoutingService {

    /**
     * Same Redis set the presence listener maintains: non-empty ⇒ a live socket.
     */
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /**
     * Deep link the match notification opens.
     */
    private static final String MATCH_DEEP_LINK = "/#match/quick";

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ContentModerationService moderationService;
    private final MatchConsentService matchConsentService;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final NotificationDispatchService notificationDispatchService;
    private final MatchMessageBufferService matchMessageBuffer;

    /**
     * Relays a text message to the sender's partner. If the content is explicit and the
     * session's 18+ consent is not GRANTED, the message is held (auto-asking the peer and
     * flagging the sender's bubble) instead of relayed.
     *
     * @param sender   the authenticated sending username
     * @param content  the message body
     * @param clientId client-generated id echoed back if the message is held
     * @throws java.lang.IllegalArgumentException if the sender has no active session
     */
    @Override
    public void relayMessage(String sender, String content, String clientId) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

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
                        "content", content, "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed text message from {} to {}", sender, recipient);
        onRelayed(recipient, event, content);
    }

    /**
     * Relays a GIF descriptor to the sender's partner over the match queue.
     *
     * @param sender the authenticated sending username
     * @param media  the GIF media descriptor
     * @throws java.lang.IllegalArgumentException if the sender has no active session
     */
    @Override
    public void relayGif(String sender, Map<String, Object> media) {
        MatchSession session = sessionService.getSessionByUser(sender)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + sender));

        String recipient = session.getUserA().equals(sender) ? session.getUserB() : session.getUserA();

        MatchServerEvent event = MatchServerEvent.builder()
                .event("GIF_RECEIVED")
                .payload(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "media", media, "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed GIF message from {} to {}", sender, recipient);
        onRelayed(recipient, event, "🎬 GIF");
    }

    /**
     * Relays a photo descriptor to the sender's partner, but only if image exchange has
     * been approved for the session.
     *
     * @param sender the authenticated sending username
     * @param media  the image media descriptor
     * @throws java.lang.IllegalArgumentException if the sender has no active session
     * @throws java.lang.IllegalStateException    if image exchange is not approved
     */
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
                        "media", media, "timestamp", System.currentTimeMillis()
                ))
                .build();

        messagingTemplate.convertAndSendToUser(recipient, "/queue/match", event);
        log.info("Relayed Image message from {} to {}", sender, recipient);
        onRelayed(recipient, event, "📷 Photo");
    }

    /**
     * Relays an anonymous typing signal (only the boolean, never the username) to the
     * partner. Silently does nothing if the sender has no active session.
     *
     * @param sender the authenticated sending username
     * @param typing whether the sender is currently typing
     */
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
