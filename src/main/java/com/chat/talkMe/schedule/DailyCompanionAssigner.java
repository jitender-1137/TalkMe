package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.DailyCompanionService;
import com.chat.talkMe.util.BackgroundTaskErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Daily Companion assigner (feature #8). Once a day, curate one companion for each eligible
 * user who doesn't already have one for today. Each user's assignment runs in its own
 * transaction ({@link DailyCompanionService#assignFor}), so one failure never aborts the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyCompanionAssigner {

    /** How many recent real accounts to curate companions for per run. */
    private static final int ELIGIBLE_BATCH = 500;

    private final UserRepository userRepository;
    private final DailyCompanionService dailyCompanionService;

    /** Daily at 00:05 server time. */
    @Scheduled(cron = "0 5 0 * * *")
    public void assignDaily() {
        try {
            List<User> eligible = userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                            PageRequest.of(0, ELIGIBLE_BATCH));
            int assigned = 0;
            for (User user : eligible) {
                try {
                    if (dailyCompanionService.assignFor(user) != null) {
                        assigned++;
                    }
                } catch (Exception e) {
                    BackgroundTaskErrors.log(log, "[daily-companion] assign for user " + user.getId(), e);
                }
            }
            log.info("[daily-companion] assigned {} companion(s) across {} eligible user(s)",
                    assigned, eligible.size());
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[daily-companion] assigner run", e);
        }
    }
}
