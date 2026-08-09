package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.AnonymousCompliment;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SendComplimentRequest;
import com.chat.talkMe.dto.response.ComplimentResponse;
import com.chat.talkMe.enums.ComplimentStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.repository.AnonymousComplimentRepository;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.AnonymousComplimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anonymous Compliments (feature ANON_COMPLIMENTS).
 *
 * <p>Secrecy is enforced structurally: the recipient's inbox view is always mapped through
 * {@link #toResponse} with {@code fromMe=false}, which populates sender identity ONLY when the
 * row is {@link ComplimentStatus#REVEALED}. Identity is exposed at exactly one transition —
 * the sender accepting a reveal — and nowhere else. Reveal-request and reveal-response are
 * both IDOR-guarded to the exact party (recipient asks, sender answers).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AnonymousComplimentServiceImpl implements AnonymousComplimentService {

    /**
     * Max compliments one user may send per rolling 24h (anti-spam cap).
     */
    private static final int DAILY_CAP = 10;

    /**
     * WS destination (client subscribes to {@code /user/queue/compliments}).
     */
    private static final String QUEUE = "/queue/compliments";

    private final AnonymousComplimentRepository complimentRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final ContentModerationService moderationService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates an anonymous compliment and pushes the (sender-less) inbox view to the recipient.
     *
     * @param sender  the authenticated author of the compliment
     * @param request holds the recipient uuid and the compliment message
     * @return the sender's own "sent" view of the persisted compliment (fromMe=true)
     * @throws com.chat.talkMe.exception.BadRequestException        on self-send (TM_962), a
     *                                                              guest/banned recipient or a block in either direction (TM_963), or empty
     *                                                              message (TM_964); {@link com.chat.talkMe.exception.NotFoundException} (TM_404)
     *                                                              if the recipient uuid does not resolve
     * @throws com.chat.talkMe.exception.ContentModerationException if the text is explicit
     * @throws com.chat.talkMe.exception.TooManyRequestsException   if the sender exceeded the
     *                                                              rolling 24h cap of {@link #DAILY_CAP} (TM_965)
     */
    @Override
    public ComplimentResponse send(User sender, SendComplimentRequest request) {
        User recipient = resolveUser(request.getRecipientUuid());

        if (recipient.getId().equals(sender.getId())) {
            throw new BadRequestException("You cannot send a compliment to yourself", "TM_962");
        }
        if (recipient.isGuest() || recipient.isBanned()) {
            throw new BadRequestException("You cannot send a compliment to this user", "TM_963");
        }
        // Never deliver across a block, in either direction.
        if (blockUserRepository.existsByUserAndBlocked(sender, recipient)
                || blockUserRepository.existsByUserAndBlocked(recipient, sender)) {
            throw new BadRequestException("You cannot send a compliment to this user", "TM_963");
        }

        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        if (message.isEmpty()) {
            throw new BadRequestException("Compliment message cannot be empty", "TM_964");
        }
        // Hard-block explicit text — a compliment is a positive, public-guidelines surface.
        if (moderationService.moderateText(message).explicit()) {
            throw new ContentModerationException(
                    "Your compliment contains content that violates our community guidelines.");
        }

        // Rolling 24h cap.
        long recent = complimentRepository.countBySenderAndCreatedAtAfter(
                sender, Instant.now().minus(Duration.ofDays(1)));
        if (recent >= DAILY_CAP) {
            throw new TooManyRequestsException(
                    "You have reached today's compliment limit. Try again later.", "TM_965");
        }

        AnonymousCompliment compliment = AnonymousCompliment.builder()
                .sender(sender)
                .recipient(recipient)
                .message(message)
                .status(ComplimentStatus.SENT)
                .build();
        complimentRepository.save(compliment);

        // Push the recipient their inbox view — sender identity is NULL (status == SENT).
        push(recipient, "compliment_received", toResponse(compliment, false));

        // Return the sender's own "sent" view.
        return toResponse(compliment, true);
    }

    /**
     * The caller's inbox — non-deleted compliments addressed to them, newest first.
     * Sender identity is populated only for rows that are {@link ComplimentStatus#REVEALED}.
     *
     * @param me the authenticated recipient
     * @return recipient-perspective views (fromMe=false)
     */
    @Override
    @Transactional(readOnly = true)
    public List<ComplimentResponse> inbox(User me) {
        List<ComplimentResponse> out = new ArrayList<>();
        for (AnonymousCompliment c : complimentRepository
                .findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(me)) {
            out.add(toResponse(c, false));
        }
        return out;
    }

    /**
     * The caller's own outgoing compliments, newest first (recipient shown, not secret).
     *
     * @param me the authenticated sender
     * @return sender-perspective views (fromMe=true)
     */
    @Override
    @Transactional(readOnly = true)
    public List<ComplimentResponse> sent(User me) {
        List<ComplimentResponse> out = new ArrayList<>();
        for (AnonymousCompliment c : complimentRepository
                .findBySenderAndIsDeletedFalseOrderByCreatedAtDesc(me)) {
            out.add(toResponse(c, true));
        }
        return out;
    }

    /**
     * Recipient asks the sender to reveal their identity, moving SENT → REVEAL_REQUESTED and
     * notifying the sender over WS. Idempotent when already REVEAL_REQUESTED.
     *
     * @param me             the authenticated recipient (only the recipient may request)
     * @param complimentUuid uuid of the compliment
     * @return the recipient's own view (sender still hidden unless already REVEALED)
     * @throws com.chat.talkMe.exception.NotFoundException   if the compliment is missing or the
     *                                                       caller is not its recipient (IDOR-guarded, TM_966)
     * @throws com.chat.talkMe.exception.BadRequestException if it was already REVEALED or
     *                                                       DECLINED (TM_967)
     */
    @Override
    public ComplimentResponse requestReveal(User me, String complimentUuid) {
        AnonymousCompliment compliment = resolveCompliment(complimentUuid);
        // IDOR guard: only the RECIPIENT may request a reveal. Treat any other caller as
        // "not found" so the endpoint never confirms a compliment they aren't party to.
        if (!compliment.getRecipient().getId().equals(me.getId())) {
            throw new NotFoundException("Compliment not found", "TM_966");
        }

        switch (compliment.getStatus()) {
            case SENT -> {
                compliment.setStatus(ComplimentStatus.REVEAL_REQUESTED);
                complimentRepository.save(compliment);
                // Notify the SENDER (who already knows the recipient). Payload is the sender's
                // "sent" view: recipient card + message, sender identity still absent.
                push(compliment.getSender(), "compliment_reveal_requested", toResponse(compliment, true));
            }
            case REVEAL_REQUESTED -> { /* idempotent — already pending */ }
            case REVEALED -> throw new BadRequestException(
                    "This compliment has already been revealed", "TM_967");
            case DECLINED -> throw new BadRequestException(
                    "The sender chose to stay anonymous", "TM_967");
        }
        // Recipient's own view — sender still hidden unless it was already REVEALED.
        return toResponse(compliment, false);
    }

    /**
     * Sender answers a pending reveal request. On accept the row becomes REVEALED (recipient
     * learns the sender — the one point identity is exposed); on decline it becomes DECLINED.
     * The recipient is notified over WS either way.
     *
     * @param me             the authenticated sender (only the sender may respond)
     * @param complimentUuid uuid of the compliment
     * @param accept         true to reveal identity, false to stay anonymous
     * @return the sender's own "sent" view of the resolved compliment
     * @throws com.chat.talkMe.exception.NotFoundException   if the compliment is missing or the
     *                                                       caller is not its sender (IDOR-guarded, TM_966)
     * @throws com.chat.talkMe.exception.BadRequestException if there is no pending reveal
     *                                                       request (status != REVEAL_REQUESTED, TM_968)
     */
    @Override
    public ComplimentResponse respondReveal(User me, String complimentUuid, boolean accept) {
        AnonymousCompliment compliment = resolveCompliment(complimentUuid);
        // IDOR guard: only the SENDER may answer a reveal request.
        if (!compliment.getSender().getId().equals(me.getId())) {
            throw new NotFoundException("Compliment not found", "TM_966");
        }
        if (compliment.getStatus() != ComplimentStatus.REVEAL_REQUESTED) {
            throw new BadRequestException("There is no pending reveal request for this compliment", "TM_968");
        }

        if (accept) {
            compliment.setStatus(ComplimentStatus.REVEALED);
            compliment.setRevealedAt(Instant.now());
            complimentRepository.save(compliment);
            // Recipient now learns the sender — this is the one point identity is exposed.
            push(compliment.getRecipient(), "compliment_revealed", toResponse(compliment, false));
        } else {
            compliment.setStatus(ComplimentStatus.DECLINED);
            complimentRepository.save(compliment);
            // Recipient is told it stays anonymous — sender identity remains null.
            push(compliment.getRecipient(), "compliment_declined", toResponse(compliment, false));
        }
        // Sender's own "sent" view of the resolved row.
        return toResponse(compliment, true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolve a user by uuid.
     *
     * @throws com.chat.talkMe.exception.NotFoundException   if no such user (TM_404)
     * @throws com.chat.talkMe.exception.BadRequestException if the uuid is malformed (TM_961)
     */
    private User resolveUser(String uuid) {
        return userRepository.findByUuid(parseUuid(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_404"));
    }

    /**
     * Resolve a compliment by uuid.
     *
     * @throws com.chat.talkMe.exception.NotFoundException   if no such compliment (TM_966)
     * @throws com.chat.talkMe.exception.BadRequestException if the uuid is malformed (TM_961)
     */
    private AnonymousCompliment resolveCompliment(String uuid) {
        return complimentRepository.findByUuid(parseUuid(uuid))
                .orElseThrow(() -> new NotFoundException("Compliment not found", "TM_966"));
    }

    /**
     * Parse an uuid string, mapping malformed/null input to a clean 400.
     *
     * @throws com.chat.talkMe.exception.BadRequestException on invalid/null input (TM_961)
     */
    private UUID parseUuid(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid id", "TM_961");
        }
    }

    /**
     * Map a compliment to its client view.
     *
     * @param fromMe true for the caller's own "sent" listing (recipient card shown, sender
     *               omitted); false for the recipient's inbox (sender shown ONLY when REVEALED).
     */
    private ComplimentResponse toResponse(AnonymousCompliment c, boolean fromMe) {
        ComplimentResponse.ComplimentResponseBuilder b = ComplimentResponse.builder()
                .uuid(c.getUuid() != null ? c.getUuid().toString() : null)
                .message(c.getMessage())
                .status(c.getStatus() != null ? c.getStatus().name() : null)
                .createdAt(c.getCreatedAt() != null ? c.getCreatedAt().toString() : null)
                .fromMe(fromMe);

        if (fromMe) {
            // The caller is the sender — recipient is not secret to them.
            User recipient = c.getRecipient();
            b.recipientName(recipient.getName())
                    .recipientUsername(recipient.getUsername())
                    .recipientAvatar(recipient.getProfileImage());
        } else if (c.getStatus() == ComplimentStatus.REVEALED) {
            // Inbox view: sender identity exposed ONLY after an accepted reveal.
            User sender = c.getSender();
            b.senderName(sender.getName())
                    .senderUsername(sender.getUsername())
                    .senderAvatar(sender.getProfileImage());
        }
        return b.build();
    }

    /**
     * Best-effort WS push to the user's {@code /queue/compliments}; swallows any send failure.
     */
    private void push(User user, String event, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getUsername(), QUEUE, Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("[AnonymousCompliment] WS push '{}' skipped for {}", event, user.getUsername(), e);
        }
    }
}
