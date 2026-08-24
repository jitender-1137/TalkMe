package com.neo.chat.schedule;

import com.neo.chat.service.StudyRoomService;
import com.neo.chat.util.BackgroundTaskErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives Study Room Pomodoro timers (STUDY_ROOMS). Polls the Redis phase-deadline ZSET on a short
 * cadence and flips FOCUS ⇄ BREAK server-authoritatively, so the client clock can never drive the
 * transition. Mirrors {@code MatchTimerReaper}: it delegates the whole batch/due-set walk to
 * {@link StudyRoomService#reapDuePhases()} and swallows any failure so a single blip never halts the
 * schedule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudyPomodoroReaper {

    private final StudyRoomService studyRoomService;

    /**
     * Scheduled tick (default every 1000ms) that delegates to
     * {@link StudyRoomService#reapDuePhases()} to flip any due Pomodoro phases. Any exception is
     * caught and logged so a single failure never halts the schedule.
     */
    @Scheduled(fixedDelayString = "${study.pomodoro-reaper.interval-ms:1000}")
    public void reap() {
        try {
            studyRoomService.reapDuePhases();
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[Study] pomodoro reaper", e);
        }
    }
}
