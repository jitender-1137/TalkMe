package com.chat.talkMe.schedule;

import com.chat.talkMe.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Permanently purges accounts whose soft-delete recovery window has elapsed.
 *
 * <p>Account deletion is a two-phase process: a request soft-deletes the account
 * (locked immediately, recoverable by logging back in), and after the configured
 * window this reaper irreversibly anonymizes it (see
 * {@link AuthService#purgeExpiredDeletedAccounts()}). Runs daily; cron is
 * overridable via {@code app.auth.purge-cron}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountPurgeReaper {

    private final AuthService authService;

    /**
     * Irreversibly purges every account whose soft-delete recovery window has elapsed.
     *
     * <p>Runs on the cron {@code ${app.auth.purge-cron:0 0 3 * * *}} (daily at 03:00 by
     * default). Delegates the actual anonymization to
     * {@link com.chat.talkMe.service.AuthService#purgeExpiredDeletedAccounts()} and only
     * logs when at least one account was purged. Any thrown exception is caught and logged
     * so a failed run never aborts the schedule.</p>
     */
    @Scheduled(cron = "${app.auth.purge-cron:0 0 3 * * *}")
    public void purgeExpiredDeletedAccounts() {
        try {
            int purged = authService.purgeExpiredDeletedAccounts();
            if (purged > 0) {
                log.info("[AccountPurge] Permanently purged {} expired deleted account(s)", purged);
            }
        } catch (Exception e) {
            log.error("[AccountPurge] Purge run failed", e);
        }
    }
}
