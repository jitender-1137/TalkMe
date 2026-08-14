package com.neo.chat.schedule;

import com.neo.chat.domain.User;
import com.neo.chat.repository.ReputationEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserReputationRepository;
import com.neo.chat.service.ReputationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Nightly reputation aggregation (features #30/#31). Folds newly-recorded ledger rows into
 * each user's {@link com.neo.chat.domain.UserReputation} snapshot. Mirrors the resilient
 * shape of {@code OutboxPublisherJob}: bounded work, per-user try/catch so one bad row can't
 * abort the whole run.
 *
 * <p>The work set is every user who either already has a snapshot OR has any ledger activity,
 * so newcomers get a snapshot on the first pass and dormant users are cheap no-ops (their
 * recompute finds zero new rows via the {@code lastLedgerIdApplied} cursor).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationAggregationJob {

    private final UserReputationRepository reputationRepository;
    private final ReputationEventRepository ledgerRepository;
    private final UserRepository userRepository;
    private final ReputationService reputationService;

    /**
     * Folds newly-recorded reputation ledger rows into each user's snapshot.
     *
     * <p>Runs on cron {@code ${app.reputation.aggregation-cron:0 15 3 * * *}} (nightly at
     * 03:15 by default). Builds the work set as the union of users who already have a
     * snapshot and users with any ledger activity, then calls
     * {@link com.neo.chat.service.ReputationService#recomputeFor(com.neo.chat.domain.User)}
     * for each (skipping ids that no longer resolve to a user). Recompute is bounded via the
     * {@code lastLedgerIdApplied} cursor, and per-user try/catch keeps one bad row from
     * aborting the run.</p>
     */
    @Scheduled(cron = "${app.reputation.aggregation-cron:0 15 3 * * *}")
    public void aggregate() {
        try {
            Set<Long> userIds = new LinkedHashSet<>();
            userIds.addAll(reputationRepository.findAllUserIds());
            userIds.addAll(ledgerRepository.findDistinctUserIds());
            if (userIds.isEmpty()) {
                return;
            }
            log.info("[reputation] Aggregating {} user(s)", userIds.size());
            int ok = 0;
            for (Long userId : userIds) {
                try {
                    User user = userRepository.findById(userId).orElse(null);
                    if (user == null) {
                        continue;
                    }
                    reputationService.recomputeFor(user);
                    ok++;
                } catch (Exception e) {
                    // Isolated per user — a failure here is retried on the next nightly run.
                    log.error("[reputation] Recompute failed for user {}", userId, e);
                }
            }
            log.info("[reputation] Aggregation complete: {}/{} recomputed", ok, userIds.size());
        } catch (Exception e) {
            log.error("[reputation] Aggregation run failed", e);
        }
    }
}
