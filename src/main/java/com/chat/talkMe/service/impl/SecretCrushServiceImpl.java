package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.SecretCrush;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.SecretCrushMatchResponse;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.SecretCrushStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.SecretCrushRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.ReputationRecorder;
import com.chat.talkMe.service.SecretCrushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Secret Crush (feature #9).
 *
 * <p>Secrecy is enforced structurally: reciprocity is only ever probed for the exact
 * directed pair (does <em>the target</em> crush back on <em>this caller</em>?), never by
 * listing who crushes on a target. A non-match response and notification carry zero signal
 * about the target's inbound crushes. Disclosure of partner identity happens strictly on a
 * confirmed mutual match, symmetrically to both participants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SecretCrushServiceImpl implements SecretCrushService {

    /**
     * Max simultaneously-active crushes one user may hold (anti-spam cap).
     */
    private static final int MAX_ACTIVE_CRUSHES = 20;

    private final SecretCrushRepository secretCrushRepository;
    private final UserRepository userRepository;
    private final CompatibilityService compatibilityService;
    private final NotificationService notificationService;
    private final ReputationRecorder reputationRecorder;
    private final SimpMessagingTemplate messagingTemplate;
    private final BlockUserRepository blockUserRepository;

    /**
     * Adds (or re-activates) the caller's crush on the target and probes for reciprocity. On a
     * mutual crush both rows flip to MATCHED, both users get a notification + WS push, both earn a
     * reputation credit, and a matched response (with compatibility) is returned; otherwise a bare
     * non-matched response revealing nothing about the target's inbound crushes. Blocks (either
     * direction) and self-crushes are refused. Class is {@code @Transactional}.
     *
     * @param crusher    the caller adding the crush
     * @param targetUuid the target user's UUID string
     * @return a matched response on mutual crush, else a non-matched response
     * @throws com.chat.talkMe.exception.BadRequestException      on self-crush, guest/banned target,
     *                                                            or invalid UUID
     * @throws com.chat.talkMe.exception.NotFoundException        if no user matches {@code targetUuid}
     * @throws com.chat.talkMe.exception.TooManyRequestsException if the active-crush cap is reached
     */
    @Override
    public SecretCrushMatchResponse addCrush(User crusher, String targetUuid) {
        User target = resolveTarget(targetUuid);

        if (target.getId().equals(crusher.getId())) {
            throw new BadRequestException("You cannot crush on yourself", "TM_910");
        }
        if (target.isGuest() || target.isBanned()) {
            throw new BadRequestException("You cannot crush on this user", "TM_911");
        }
        // Never form a crush (and never a match) across a block, in either direction.
        if (blockUserRepository.existsByUserAndBlocked(crusher, target)
                || blockUserRepository.existsByUserAndBlocked(target, crusher)) {
            return SecretCrushMatchResponse.builder().matched(false).build();
        }

        // Upsert the caller's crush row (idempotent on the unique pair).
        SecretCrush crush = secretCrushRepository.findByCrusherAndTarget(crusher, target).orElse(null);
        if (crush != null && crush.getStatus() == SecretCrushStatus.MATCHED) {
            // Already matched — just re-surface the match, don't re-notify.
            return matchedResponse(crusher, target);
        }
        if (crush == null) {
            // Enforce the active-crush cap only when creating a genuinely new crush.
            long active = secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE);
            if (active >= MAX_ACTIVE_CRUSHES) {
                throw new TooManyRequestsException(
                        "You have reached the maximum number of active crushes", "TM_912");
            }
            crush = SecretCrush.builder()
                    .crusher(crusher)
                    .target(target)
                    .status(SecretCrushStatus.ACTIVE)
                    .build();
        } else if (crush.getStatus() != SecretCrushStatus.ACTIVE) {
            // Re-activating a previously withdrawn crush also counts against the cap.
            long active = secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE);
            if (active >= MAX_ACTIVE_CRUSHES) {
                throw new TooManyRequestsException(
                        "You have reached the maximum number of active crushes", "TM_912");
            }
            crush.setStatus(SecretCrushStatus.ACTIVE);
        }
        secretCrushRepository.save(crush);

        // Reciprocity probe — narrow, symmetric, never enumerates who crushes on the target.
        SecretCrush reciprocal = secretCrushRepository
                .findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.ACTIVE)
                .orElse(null);

        if (reciprocal == null) {
            // No match. Reveal nothing about the target's inbound crushes.
            return SecretCrushMatchResponse.builder().matched(false).build();
        }

        // ── Mutual match ──────────────────────────────────────────────────────────
        crush.setStatus(SecretCrushStatus.MATCHED);
        reciprocal.setStatus(SecretCrushStatus.MATCHED);
        secretCrushRepository.save(crush);
        secretCrushRepository.save(reciprocal);

        notifyMatch(crusher, target, crush.getUuid());
        notifyMatch(target, crusher, reciprocal.getUuid());

        // A mutual match is a positive new connection for both sides.
        safeRecord(crusher.getId(), crush.getUuid());
        safeRecord(target.getId(), reciprocal.getUuid());

        return matchedResponse(crusher, target);
    }

    /**
     * Withdraws the caller's crush on the target (WITHDRAWN); a no-op if none exists. If it was a
     * mutual match, the partner's side is demoted back to one-sided ACTIVE and an "UNMATCHED" WS
     * event is pushed to them. Class is {@code @Transactional}.
     *
     * @param crusher    the caller withdrawing the crush
     * @param targetUuid the target user's UUID string
     * @throws com.chat.talkMe.exception.BadRequestException if {@code targetUuid} is not a valid UUID
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches {@code targetUuid}
     */
    @Override
    public void withdrawCrush(User crusher, String targetUuid) {
        User target = resolveTarget(targetUuid);
        secretCrushRepository.findByCrusherAndTarget(crusher, target).ifPresent(crush -> {
            boolean wasMatched = crush.getStatus() == SecretCrushStatus.MATCHED;
            crush.setStatus(SecretCrushStatus.WITHDRAWN);
            secretCrushRepository.save(crush);
            // If this was a mutual match, demote the partner's side back to a one-sided
            // ACTIVE crush so they no longer see a (now broken) match.
            if (wasMatched) {
                secretCrushRepository
                        .findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.MATCHED)
                        .ifPresent(recipe -> {
                            recipe.setStatus(SecretCrushStatus.ACTIVE);
                            secretCrushRepository.save(recipe);
                        });
                // Tell the demoted partner so their client refreshes (no phantom "Matched").
                try {
                    messagingTemplate.convertAndSendToUser(target.getUsername(), "/queue/secret-crush",
                            Map.of("type", "UNMATCHED"));
                } catch (Exception e) {
                    log.debug("[SecretCrush] unmatch push skipped for {}", target.getUsername(), e);
                }
            }
        });
    }

    /**
     * Lists the caller's crushes: still one-sided ACTIVE crushes (target card only, no reciprocity
     * revealed) and confirmed MATCHED crushes (partner identity + compatibility disclosed).
     * Read-only transaction.
     *
     * @param user the caller
     * @return the caller's active and matched crush entries
     */
    @Override
    @Transactional(readOnly = true)
    public List<SecretCrushMatchResponse> listMine(User user) {
        List<SecretCrushMatchResponse> out = new ArrayList<>();
        // The caller's own active (still one-sided) crushes.
        for (SecretCrush c : secretCrushRepository.findByCrusherAndStatus(user, SecretCrushStatus.ACTIVE)) {
            out.add(entry(c.getTarget(), false, null));
        }
        // The caller's matches — partner identity + compatibility disclosed.
        for (SecretCrush c : secretCrushRepository.findByCrusherAndStatus(user, SecretCrushStatus.MATCHED)) {
            User partner = c.getTarget();
            out.add(entry(partner, true, compatibilityService.score(user, partner)));
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolves a target-user UUID string to the {@link User} entity.
     *
     * @throws com.chat.talkMe.exception.BadRequestException if the string is not a valid UUID
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches it
     */
    private User resolveTarget(String targetUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_913");
        }
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_404"));
    }

    /**
     * Response for a confirmed match — includes partner card + compatibility.
     */
    private SecretCrushMatchResponse matchedResponse(User self, User partner) {
        return entry(partner, true, safeScore(self, partner));
    }

    /**
     * Computes the compatibility score for a pair, returning null if scoring throws.
     */
    private CompatibilityScore safeScore(User self, User partner) {
        try {
            // Both sides are loaded within this transaction; scoring is pure.
            return compatibilityService.score(self, partner);
        } catch (Exception e) {
            return null;
        }
    }

    private SecretCrushMatchResponse entry(User partner, boolean matched, CompatibilityScore score) {
        return SecretCrushMatchResponse.builder()
                .matched(matched)
                .partnerUuid(partner.getUuid() != null ? partner.getUuid().toString() : null)
                .partnerName(partner.getName())
                .partnerUsername(partner.getUsername())
                .partnerAvatar(partner.getProfileImage())
                .partnerMood(partner.getMood() != null ? partner.getMood().name() : null)
                .partnerCountry(partner.getCountry())
                .compatibility(score)
                .chatId(null)
                .build();
    }

    /**
     * Notifies one side of a mutual match: persists a SECRET_CRUSH_MATCHED notification and pushes a
     * "MATCHED" WS event to the recipient's {@code /queue/secret-crush}. Both steps are best-effort
     * (failures logged, not thrown).
     */
    private void notifyMatch(User recipient, User partner, UUID crushUuid) {
        try {
            notificationService.createNotification(
                    recipient,
                    "It's a match!",
                    "You and " + partner.getName() + " have a secret crush on each other.",
                    "SECRET_CRUSH_MATCHED",
                    partner.getUuid().toString(),
                    partner,
                    partner.getProfileImage()
            );
        } catch (Exception e) {
            log.warn("[SecretCrush] failed to persist match notification for {}", recipient.getUsername(), e);
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MATCHED");
            payload.put("crushUuid", crushUuid.toString());
            payload.put("partner", entry(partner, true, null));
            messagingTemplate.convertAndSendToUser(recipient.getUsername(), "/queue/secret-crush", payload);
        } catch (Exception e) {
            log.warn("[SecretCrush] failed to push match event to {}", recipient.getUsername(), e);
        }
    }

    /**
     * Best-effort reputation credit (CONVERSATION_STARTED) for a new match; swallows any failure.
     */
    private void safeRecord(Long userId, UUID sourceRef) {
        try {
            reputationRecorder.record(userId, ReputationEventType.CONVERSATION_STARTED, sourceRef.toString());
        } catch (Exception e) {
            log.debug("[SecretCrush] reputation record skipped for {}", userId, e);
        }
    }
}
