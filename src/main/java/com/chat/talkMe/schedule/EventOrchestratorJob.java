package com.chat.talkMe.schedule;

import com.chat.talkMe.service.EventService;
import com.chat.talkMe.util.BackgroundTaskErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the Midnight Events (feature #24) lifecycle. Every tick it spins up rooms for events
 * whose start time has arrived (and notifies RSVPs) and ends live events whose close time has
 * elapsed. Each event is transitioned in its own transaction inside the service, so one bad
 * event never blocks the rest; this job only orchestrates and swallows tick-level failures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOrchestratorJob {

    private final EventService eventService;

    @Scheduled(fixedDelayString = "${app.midnight-events.orchestrator-ms:30000}")
    public void tick() {
        try {
            int started = eventService.startDueEvents();
            if (started > 0) {
                log.debug("[midnight-events] started {} event(s)", started);
            }
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[midnight-events] start-due run", e);
        }
        try {
            int ended = eventService.endDueEvents();
            if (ended > 0) {
                log.debug("[midnight-events] ended {} event(s)", ended);
            }
        } catch (Exception e) {
            BackgroundTaskErrors.log(log, "[midnight-events] end-due run", e);
        }
    }
}
