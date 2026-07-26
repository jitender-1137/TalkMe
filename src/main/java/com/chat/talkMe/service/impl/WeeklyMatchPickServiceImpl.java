package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.WeeklyMatchPick;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.WeeklyMatchPickResponse;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.WeeklyMatchPickRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.WeeklyMatchPickService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Weekly Match Picks (feature #28). Generation ranks a bounded candidate pool by the
 * deterministic {@link CompatibilityService} and persists the top {@value #PICK_COUNT}
 * for the current ISO week. Reads recompute the full compatibility breakdown live so the
 * highlights/explanation stay current even though only the score+rank are stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WeeklyMatchPickServiceImpl implements WeeklyMatchPickService {

    /** How many candidates to score each run — bounds the O(users * pool) job cost. */
    private static final int CANDIDATE_POOL = 200;
    /** How many top picks to persist per user per week. */
    private static final int PICK_COUNT = 10;

    private final WeeklyMatchPickRepository weeklyMatchPickRepository;
    private final UserRepository userRepository;
    private final CompatibilityService compatibilityService;
    private final BlockUserRepository blockUserRepository;

    @Override
    @Transactional(readOnly = true)
    public List<WeeklyMatchPickResponse> getCurrent(User user) {
        LocalDate weekStart = WeeklyMatchPickService.weekStart();
        List<WeeklyMatchPick> picks =
                weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart);

        // Blocks can be created AFTER generation, so re-filter both directions on read (bounded
        // to two small queries regardless of pick count — no per-pick N+1).
        Set<Long> blocked = new HashSet<>();
        for (BlockUser b : blockUserRepository.findByUser(user)) {
            blocked.add(b.getBlocked().getId());
        }
        for (BlockUser b : blockUserRepository.findByBlocked(user)) {
            blocked.add(b.getUser().getId());
        }

        List<WeeklyMatchPickResponse> out = new ArrayList<>(picks.size());
        for (WeeklyMatchPick pick : picks) {
            User picked = pick.getPickedUser();
            // Skip picks whose target has since been deactivated or blocked (either direction).
            if (picked == null || picked.isDeleted() || picked.isBanned()
                    || blocked.contains(picked.getId())) {
                continue;
            }
            CompatibilityScore compatibility = compatibilityService.score(user, picked);
            out.add(WeeklyMatchPickResponse.builder()
                    .id(picked.getUuid() != null ? picked.getUuid().toString() : null)
                    .name(picked.getName())
                    .username(picked.getUsername())
                    .avatar(picked.getProfileImage())
                    .mood(picked.getMood() != null ? picked.getMood().name() : null)
                    .country(picked.getCountry())
                    .age(picked.getAge())
                    .rank(pick.getRank())
                    .score(pick.getScore())
                    .compatibility(compatibility)
                    .build());
        }
        return out;
    }

    @Override
    public void generateFor(User user) {
        if (user == null || user.isGuest() || user.isBanned() || user.isDeleted()) {
            return;
        }
        LocalDate weekStart = WeeklyMatchPickService.weekStart();

        // Idempotent: clear any existing picks for this user/week before regenerating.
        List<WeeklyMatchPick> existing =
                weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart);
        if (!existing.isEmpty()) {
            weeklyMatchPickRepository.deleteAll(existing);
        }

        // Exclude blocks in both directions. Batch-load both sets up front (two queries) instead
        // of probing existsByUserAndBlocked per candidate (which was an N+1 over the whole pool).
        Set<Long> excluded = new HashSet<>();
        for (BlockUser b : blockUserRepository.findByUser(user)) {
            excluded.add(b.getBlocked().getId());
        }
        for (BlockUser b : blockUserRepository.findByBlocked(user)) {
            excluded.add(b.getUser().getId());
        }

        List<User> pool = userRepository
                .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                        PageRequest.of(0, CANDIDATE_POOL));

        List<ScoredCandidate> scored = new ArrayList<>();
        for (User candidate : pool) {
            if (candidate.getId() == null || candidate.getId().equals(user.getId())) {
                continue;
            }
            if (excluded.contains(candidate.getId())) {
                continue;
            }
            int overall = compatibilityService.score(user, candidate).getOverall();
            scored.add(new ScoredCandidate(candidate, overall));
        }
        scored.sort(Comparator.comparingInt(ScoredCandidate::score).reversed());

        int limit = Math.min(PICK_COUNT, scored.size());
        List<WeeklyMatchPick> toSave = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoredCandidate sc = scored.get(i);
            toSave.add(WeeklyMatchPick.builder()
                    .user(user)
                    .pickedUser(sc.user())
                    .score(sc.score())
                    .weekStart(weekStart)
                    .rank(i + 1)
                    .build());
        }
        if (!toSave.isEmpty()) {
            weeklyMatchPickRepository.saveAll(toSave);
        }
        log.debug("[WeeklyPicks] generated {} picks for user {} (week {})",
                toSave.size(), user.getId(), weekStart);
    }

    @Override
    public void pruneOlderThan(LocalDate weekStart) {
        weeklyMatchPickRepository.deleteByWeekStartBefore(weekStart);
    }

    private record ScoredCandidate(User user, int score) {
    }
}
