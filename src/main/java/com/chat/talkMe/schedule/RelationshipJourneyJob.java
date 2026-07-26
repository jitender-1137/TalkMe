package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.service.RelationshipJourneyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Nightly derivation of Relationship Journey milestones (feature #19, RELATIONSHIP_JOURNEY).
 *
 * <p>Iterates active friendships and calls {@link RelationshipJourneyService#materializeFor}
 * once per unique pair. Each call runs in its own transaction (the service is {@code
 * @Transactional} and this job is not), so one bad pair never aborts the batch, and the
 * upserts are idempotent so re-runs never duplicate a milestone.
 *
 * <p>Cost is bounded to the most-recent {@value #MAX_FRIENDSHIP_ROWS} friendship rows — a
 * pragmatic v1 cap. Scaling past that wants a dedicated {@code findByIsDeletedFalse(Pageable)}
 * cursor on {@code FriendRepository} (that repo is shared; see wiringSpec).
 *
 * <p>Both the cron and zone are overridable via {@code app.relationship-journey.cron} /
 * {@code app.relationship-journey.zone}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationshipJourneyJob {

    /** Upper bound on friendship rows scanned per run — keeps the batch cost bounded. */
    private static final int MAX_FRIENDSHIP_ROWS = 5000;

    private final FriendRepository friendRepository;
    private final RelationshipJourneyService relationshipJourneyService;

    @Scheduled(
            cron = "${app.relationship-journey.cron:0 30 3 * * *}",
            zone = "${app.relationship-journey.zone:Asia/Kolkata}")
    public void deriveMilestones() {
        log.info("[Journey] nightly milestone derivation starting");

        // FriendRepository has no "active-only" bounded finder; page findAll and filter here.
        // Friend own-columns are loaded eagerly; the user/friend associations are lazy proxies
        // whose id is available without a DB hit, which is all materializeFor needs.
        List<Friend> friendships =
                friendRepository.findAll(PageRequest.of(0, MAX_FRIENDSHIP_ROWS)).getContent();

        Set<String> seenPairs = new HashSet<>();
        int ok = 0;
        int failed = 0;
        int skipped = 0;

        for (Friend friendship : friendships) {
            if (friendship.isDeleted() || friendship.getUser() == null || friendship.getFriend() == null) {
                skipped++;
                continue;
            }
            Long aId = friendship.getUser().getId();
            Long bId = friendship.getFriend().getId();
            if (aId == null || bId == null || aId.equals(bId)) {
                skipped++;
                continue;
            }
            // Dedup the two directional rows of the same friendship into one normalized pair.
            String key = Math.min(aId, bId) + ":" + Math.max(aId, bId);
            if (!seenPairs.add(key)) {
                continue;
            }
            try {
                relationshipJourneyService.materializeFor(friendship.getUser(), friendship.getFriend());
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("[Journey] materialize failed for pair {}: {}", key, e.getMessage());
            }
        }

        log.info("[Journey] done: {} pairs materialized, {} failed, {} rows skipped",
                ok, failed, skipped);
    }
}
