package com.chat.talkMe.schedule;

import com.chat.talkMe.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Reaps temporary posts (feature #22) once their TTL elapses. The feed queries already hide an
 * expired post the moment {@code expiresAt} passes, so this is the cleanup backstop that removes
 * it from listings/counts entirely. Mirrors {@link SelfDestructReaper}: bounded per tick, its own
 * try/catch so one bad run never aborts the schedule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostExpiryReaper {

    private final PostService postService;

    @Scheduled(fixedDelayString = "${app.temporary-posts.reaper-ms:60000}")
    public void reap() {
        try {
            int reaped = postService.reapExpiredPosts(Instant.now());
            if (reaped > 0) {
                log.debug("[temporary-posts] reaper removed {} expired post(s)", reaped);
            }
        } catch (Exception e) {
            log.error("[temporary-posts] reaper run failed", e);
        }
    }
}
