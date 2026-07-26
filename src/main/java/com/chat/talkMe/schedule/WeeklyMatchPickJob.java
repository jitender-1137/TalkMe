package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.WeeklyMatchPickRepository;
import com.chat.talkMe.service.WeeklyMatchPickService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Regenerates Weekly Match Picks (feature #28) every Monday at 09:00 and prunes prior
 * weeks. Runs per-user through {@link WeeklyMatchPickService#generateFor(User)} so each
 * user's generation is its own transaction — one failure never aborts the whole batch.
 *
 * <p>Cost is O(eligibleUsers * candidatePool): bounded here to the most-recent
 * {@value #MAX_ELIGIBLE_USERS} active accounts, each scored against a 200-candidate pool.
 * Both the cron and zone are overridable via {@code app.weekly-picks.cron} /
 * {@code app.weekly-picks.zone}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyMatchPickJob {

    /** Upper bound on how many users get picks each run — keeps the batch cost bounded. */
    private static final int MAX_ELIGIBLE_USERS = 1000;

    private final WeeklyMatchPickService weeklyMatchPickService;
    private final WeeklyMatchPickRepository weeklyMatchPickRepository;
    private final UserRepository userRepository;

    @Scheduled(
            cron = "${app.weekly-picks.cron:0 0 9 * * MON}",
            zone = "${app.weekly-picks.zone:Asia/Kolkata}")
    public void regenerateWeeklyPicks() {
        LocalDate weekStart = WeeklyMatchPickService.weekStart();
        log.info("[WeeklyPicks] regenerating picks for week {}", weekStart);

        // Prune stale weeks first so the table stays small (delete runs in the service's tx).
        try {
            weeklyMatchPickService.pruneOlderThan(weekStart);
        } catch (Exception e) {
            log.warn("[WeeklyPicks] prune of old weeks failed: {}", e.getMessage());
        }

        List<User> eligible = userRepository
                .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                        PageRequest.of(0, MAX_ELIGIBLE_USERS));

        int ok = 0;
        int failed = 0;
        for (User user : eligible) {
            try {
                weeklyMatchPickService.generateFor(user);
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("[WeeklyPicks] generation failed for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }
        log.info("[WeeklyPicks] done: {} users generated, {} failed", ok, failed);
    }
}
