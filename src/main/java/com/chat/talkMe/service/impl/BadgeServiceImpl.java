package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BadgeEndorsement;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserBadge;
import com.chat.talkMe.dto.response.BadgeResponse;
import com.chat.talkMe.enums.BadgeType;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.BadgeEndorsementRepository;
import com.chat.talkMe.repository.UserBadgeRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.BadgeService;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Peer-endorseable cosmetic badges (feature #30).
 *
 * <p>Abuse resistance is structural: self-endorsement is rejected, and the unique constraint
 * on (endorser, recipient, badge_type) means the persisted endorsement count is a distinct-peer
 * count that cannot be inflated by re-clicking. A badge is awarded when that count crosses
 * {@link #AWARD_THRESHOLD}; the award records reputation events but never gates any feature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BadgeServiceImpl implements BadgeService {

    /** Distinct peer endorsements required before a badge is awarded. */
    private static final int AWARD_THRESHOLD = 3;

    private final UserBadgeRepository userBadgeRepository;
    private final BadgeEndorsementRepository badgeEndorsementRepository;
    private final UserRepository userRepository;
    private final ReputationRecorder reputationRecorder;

    @Override
    @Transactional(readOnly = true)
    public List<BadgeResponse> listBadges(String userUuid) {
        User user = resolveUser(userUuid);
        List<BadgeResponse> out = new ArrayList<>();
        for (UserBadge badge : userBadgeRepository.findByUser(user)) {
            out.add(toResponse(badge));
        }
        return out;
    }

    @Override
    public BadgeResponse endorse(User endorser, String recipientUuid, BadgeType badgeType) {
        if (badgeType == null) {
            throw new BadRequestException("A badge type is required", "TM_920");
        }
        // Guests/banned accounts can't hand out endorsements (they'd inflate a peer's count).
        if (endorser.isGuest() || endorser.isBanned()) {
            throw new ForbiddenException("You cannot endorse right now", "TM_923");
        }
        User recipient = resolveUser(recipientUuid);

        if (recipient.getId().equals(endorser.getId())) {
            throw new BadRequestException("You cannot endorse yourself", "TM_921");
        }
        // A badge is a cosmetic reward on a real, active account — never on a guest, a banned
        // user, or a soft-deleted account.
        if (recipient.isGuest() || recipient.isBanned() || recipient.isDeleted()) {
            throw new BadRequestException("You cannot endorse this user", "TM_924");
        }

        // Idempotent on the unique (endorser, recipient, type) pair — re-endorsing is a no-op.
        if (badgeEndorsementRepository
                .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, badgeType)) {
            return currentState(recipient, badgeType);
        }

        try {
            badgeEndorsementRepository.save(BadgeEndorsement.builder()
                    .endorser(endorser)
                    .recipient(recipient)
                    .badgeType(badgeType)
                    .build());
        } catch (DataIntegrityViolationException race) {
            // Concurrent duplicate endorsement lost the unique-constraint race — treat as no-op.
            return currentState(recipient, badgeType);
        }

        long distinctEndorsers = badgeEndorsementRepository
                .countByRecipientAndBadgeType(recipient, badgeType);

        UserBadge badge = userBadgeRepository.findByUserAndBadgeType(recipient, badgeType)
                .orElseGet(() -> UserBadge.builder()
                        .user(recipient)
                        .badgeType(badgeType)
                        .endorsementCount(0)
                        .build());

        boolean wasEarned = badge.getAwardedAt() != null;
        badge.setEndorsementCount((int) distinctEndorsers);

        boolean nowEarned = distinctEndorsers >= AWARD_THRESHOLD;
        boolean firstAward = nowEarned && !wasEarned;
        if (firstAward) {
            badge.setAwardedAt(Instant.now());
        }
        badge = userBadgeRepository.save(badge);

        // Reputation is cosmetic bookkeeping only. Every accepted endorsement counts (capped by
        // the ledger's per-type daily/diminishing rules); a first award additionally records the
        // badge. sourceRef keys keep the ledger's own dedupe meaningful.
        safeRecord(recipient.getId(), ReputationEventType.ENDORSEMENT_RECEIVED,
                "badge:" + badgeType.name() + ":from:" + endorser.getId());
        if (firstAward) {
            safeRecord(recipient.getId(), ReputationEventType.BADGE_EARNED,
                    "badge:" + badgeType.name());
        }

        return toResponse(badge);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private BadgeResponse currentState(User recipient, BadgeType badgeType) {
        return userBadgeRepository.findByUserAndBadgeType(recipient, badgeType)
                .map(this::toResponse)
                .orElseGet(() -> BadgeResponse.builder()
                        .type(badgeType.name())
                        .label(badgeType.getLabel())
                        .endorsementCount(
                                (int) badgeEndorsementRepository
                                        .countByRecipientAndBadgeType(recipient, badgeType))
                        .earned(false)
                        .build());
    }

    private BadgeResponse toResponse(UserBadge badge) {
        return BadgeResponse.builder()
                .type(badge.getBadgeType().name())
                .label(badge.getBadgeType().getLabel())
                .awardedAt(badge.getAwardedAt() != null ? badge.getAwardedAt().toString() : null)
                .endorsementCount(badge.getEndorsementCount())
                .earned(badge.getAwardedAt() != null)
                .build();
    }

    private User resolveUser(String userUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_922");
        }
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_404"));
    }

    private void safeRecord(Long userId, ReputationEventType type, String sourceRef) {
        try {
            reputationRecorder.record(userId, type, sourceRef);
        } catch (Exception e) {
            log.debug("[Badge] reputation record skipped for {} ({})", userId, type, e);
        }
    }
}
