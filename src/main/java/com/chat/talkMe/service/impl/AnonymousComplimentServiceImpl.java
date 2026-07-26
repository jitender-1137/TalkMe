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

    /** Max compliments one user may send per rolling 24h (anti-spam cap). */
    private static final int DAILY_CAP = 10;

    /** WS destination (client subscribes to {@code /user/queue/compliments}). */
    private static final String QUEUE = "/queue/compliments";

    private final AnonymousComplimentRepository complimentRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final ContentModerationService moderationService;
    private final SimpMessagingTemplate messagingTemplate;

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
        if (moderationService.moderateText(message).isExplicit()) {
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

    private User resolveUser(String uuid) {
        return userRepository.findByUuid(parseUuid(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_404"));
    }

    private AnonymousCompliment resolveCompliment(String uuid) {
        return complimentRepository.findByUuid(parseUuid(uuid))
                .orElseThrow(() -> new NotFoundException("Compliment not found", "TM_966"));
    }

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

    private void push(User user, String event, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getUsername(), QUEUE, Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("[AnonymousCompliment] WS push '{}' skipped for {}", event, user.getUsername(), e);
        }
    }
}
