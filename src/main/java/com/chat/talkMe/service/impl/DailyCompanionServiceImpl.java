package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.DailyCompanion;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.DailyCompanionResponse;
import com.chat.talkMe.enums.CompanionStatus;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.DailyCompanionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.DailyCompanionService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DailyCompanionServiceImpl implements DailyCompanionService {

    /** Candidate pool size drawn from the most-recent real accounts. */
    private static final int CANDIDATE_POOL = 100;
    /** How many days back to avoid re-pairing the same companion. */
    private static final int RECENT_PAIRING_WINDOW = 14;
    /** Decision window before a pairing expires. */
    private static final Duration COMPANION_TTL = Duration.ofHours(24);

    private final DailyCompanionRepository dailyCompanionRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final CompatibilityService compatibilityService;
    private final NotificationService notificationService;
    private final ReputationRecorder reputationRecorder;

    @Override
    @Transactional(readOnly = true)
    public DailyCompanionResponse getToday(User user) {
        return dailyCompanionRepository.findByUserAndPairDate(user, LocalDate.now())
                .map(this::toResponse)
                .orElseGet(DailyCompanionServiceImpl::empty);
    }

    @Override
    public DailyCompanionResponse act(User user, String action) {
        if (action == null || action.isBlank()) {
            throw new BadRequestException("action is required", "TM_400");
        }
        DailyCompanion pairing = dailyCompanionRepository.findByUserAndPairDate(user, LocalDate.now())
                .orElseThrow(() -> new BadRequestException("No companion assigned today", "TM_400"));

        // Don't let a decision resurrect an already-final pairing.
        if (pairing.getStatus() == CompanionStatus.ENDED
                || pairing.getStatus() == CompanionStatus.CONVERTED_FRIENDS) {
            throw new BadRequestException("This companion decision is already final", "TM_400");
        }

        switch (action.trim().toUpperCase()) {
            case "STAY_FRIENDS" ->
                // Marks intent to keep the connection. (Establishing the actual friend link
                // via FriendService is a deliberate follow-up; no reputation is awarded here
                // to avoid a one-tap FRIEND_LASTING farming vector — the nightly job that
                // verifies mutual+lasting friendships is the source of that reward.)
                pairing.setStatus(CompanionStatus.CONVERTED_FRIENDS);
            // CONTINUE keeps chatting past the window — extend expiry so the reaper won't EXPIRE it.
            case "CONTINUE" -> {
                pairing.setStatus(CompanionStatus.ACTIVE);
                pairing.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
            }
            case "END" -> pairing.setStatus(CompanionStatus.ENDED);
            default -> throw new BadRequestException(
                    "action must be STAY_FRIENDS, CONTINUE or END", "TM_400");
        }
        dailyCompanionRepository.save(pairing);
        return toResponse(pairing);
    }

    @Override
    public DailyCompanion assignFor(User user) {
        LocalDate today = LocalDate.now();
        // Re-load as a MANAGED entity so lazy personality/interests are scorable and reachable.
        User me = userRepository.findById(user.getId()).orElse(user);

        if (dailyCompanionRepository.existsByUserAndPairDate(me, today)) {
            return dailyCompanionRepository.findByUserAndPairDate(me, today).orElse(null);
        }
        if (me.isGuest() || me.isBanned() || me.isDeleted()) {
            return null;
        }

        // Exclusions: self, recently-paired companions, and users in either block direction.
        Set<Long> excluded = new HashSet<>();
        excluded.add(me.getId());
        dailyCompanionRepository.findByUserOrderByPairDateDesc(me).stream()
                .limit(RECENT_PAIRING_WINDOW)
                .forEach(p -> excluded.add(p.getCompanion().getId()));
        for (BlockUser b : blockUserRepository.findByUser(me)) {
            excluded.add(b.getBlocked().getId());
        }

        List<User> pool = userRepository
                .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                        PageRequest.of(0, CANDIDATE_POOL));

        User best = null;
        CompatibilityScore bestScore = null;
        for (User candidate : pool) {
            if (excluded.contains(candidate.getId())) {
                continue;
            }
            // Skip if the candidate has blocked me.
            if (blockUserRepository.existsByUserAndBlocked(candidate, me)) {
                continue;
            }
            CompatibilityScore s = compatibilityService.score(me, candidate);
            if (bestScore == null || s.getOverall() > bestScore.getOverall()) {
                best = candidate;
                bestScore = s;
            }
        }

        if (best == null) {
            log.debug("[daily-companion] no eligible candidate for user {}", me.getId());
            return null;
        }

        DailyCompanion pairing = DailyCompanion.builder()
                .user(me)
                .companion(best)
                .pairDate(today)
                .status(CompanionStatus.ACTIVE)
                .expiresAt(Instant.now().plus(COMPANION_TTL))
                .compatibilityScore(bestScore.getOverall())
                .build();
        pairing = dailyCompanionRepository.save(pairing);

        try {
            notificationService.createNotification(
                    me,
                    "Your Daily Companion is here",
                    best.getName() + " is your companion for today.",
                    "DAILY_COMPANION",
                    pairing.getUuid().toString(),
                    best,
                    best.getProfileImage()
            );
        } catch (Exception e) {
            log.warn("[daily-companion] notify failed for user {}: {}", me.getId(), e.getMessage());
        }
        return pairing;
    }

    @Override
    public int reapExpired(Instant now) {
        List<DailyCompanion> due =
                dailyCompanionRepository.findByStatusAndExpiresAtBefore(CompanionStatus.ACTIVE, now);
        for (DailyCompanion p : due) {
            p.setStatus(CompanionStatus.EXPIRED);
        }
        if (!due.isEmpty()) {
            dailyCompanionRepository.saveAll(due);
        }
        return due.size();
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────

    private DailyCompanionResponse toResponse(DailyCompanion p) {
        User c = p.getCompanion();
        CompatibilityScore compat = null;
        if (p.getUser() != null && c != null) {
            try {
                compat = compatibilityService.score(p.getUser(), c);
            } catch (Exception e) {
                log.debug("[daily-companion] could not score pairing {}: {}", p.getUuid(), e.getMessage());
            }
        }
        return DailyCompanionResponse.builder()
                .pairingUuid(p.getUuid().toString())
                .pairDate(p.getPairDate())
                .status(p.getStatus().name())
                .expiresAt(p.getExpiresAt())
                .companionUuid(c != null ? c.getUuid().toString() : null)
                .name(c != null ? c.getName() : null)
                .username(c != null ? c.getUsername() : null)
                .avatar(c != null ? c.getProfileImage() : null)
                .age(c != null ? c.getAge() : null)
                .country(c != null ? c.getCountry() : null)
                .mood(c != null && c.getMood() != null ? c.getMood().name() : null)
                .compatibility(compat)
                .build();
    }

    private static DailyCompanionResponse empty() {
        return DailyCompanionResponse.builder()
                .pairDate(LocalDate.now())
                .build();
    }
}
