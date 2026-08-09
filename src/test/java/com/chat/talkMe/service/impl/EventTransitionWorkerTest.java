package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.EventRsvp;
import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.enums.EventStatus;
import com.chat.talkMe.enums.RsvpStatus;
import com.chat.talkMe.repository.EventRsvpRepository;
import com.chat.talkMe.repository.ScheduledEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link EventTransitionWorker} — the per-event {@code REQUIRES_NEW}
 * transition worker for Midnight Events. Covers {@code startEvent} (guard states, room spin-up,
 * LIVE flip + RSVP notify, retire-if-past-end) and {@code endEvent} (LIVE → ENDED), plus the
 * best-effort per-recipient notify fan-out that must never fail the transition.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTransitionWorker (unit)")
class EventTransitionWorkerTest {

    private static final UUID EVENT_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String ROOM_UUID = "bbbbbbbb-0000-0000-0000-000000000002";

    @Mock private ScheduledEventRepository scheduledEventRepository;
    @Mock private EventRsvpRepository eventRsvpRepository;
    @Mock private UserRepository userRepository;
    @Mock private GroupService groupService;
    @Mock private NotificationService notificationService;

    private EventTransitionWorker worker;

    private User host;

    @BeforeEach
    void setUp() {
        worker = new EventTransitionWorker(scheduledEventRepository, eventRsvpRepository,
                userRepository, groupService, notificationService);
        host = User.builder().username("host").name("Host").email("h@e.com").build();
        host.setId(1L);
    }

    private ScheduledEvent event(EventStatus status, Instant endAt) {
        ScheduledEvent e = ScheduledEvent.builder()
                .host(host)
                .title("Late Night Jam")
                .description("music")
                .category("music")
                .startAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .endAt(endAt)
                .status(status)
                .build();
        e.setId(10L);
        e.setUuid(EVENT_UUID);
        return e;
    }

    @Nested
    @DisplayName("startEvent")
    class StartEvent {

        @Test
        @DisplayName("SCHEDULED with a future close time → spins up room, flips LIVE, notifies RSVPs, returns true")
        void happyPath() {
            ScheduledEvent e = event(EventStatus.SCHEDULED, Instant.now().plus(1, ChronoUnit.HOURS));
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(groupService.createGroup(any(CreateGroupRequest.class), eq(host)))
                    .thenReturn(ChatResponse.builder().id(ROOM_UUID).build());
            EventRsvp r1 = EventRsvp.builder().event(e).user(host).status(RsvpStatus.GOING).build();
            when(eventRsvpRepository.findByEventAndStatusIn(eq(e), any())).thenReturn(List.of(r1));

            boolean started = worker.startEvent(10L);

            assertThat(started).isTrue();
            assertThat(e.getStatus()).isEqualTo(EventStatus.LIVE);
            assertThat(e.getRoomChatUuid()).isEqualTo(ROOM_UUID);
            assertThat(e.isReminderSent()).isTrue();
            verify(scheduledEventRepository).save(e);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(host));
            assertThat(req.getValue().getName()).isEqualTo("Late Night Jam");
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getVisibility()).isEqualTo("PUBLIC");
            assertThat(req.getValue().getAllowNonFriends()).isTrue();
            assertThat(req.getValue().getCategory()).isEqualTo("music");

            verify(notificationService).createNotification(eq(host), anyString(), anyString(),
                    eq("MIDNIGHT_EVENT_LIVE"), eq(EVENT_UUID.toString()));
        }

        @Test
        @DisplayName("no endAt → still starts normally")
        void noEndAt() {
            ScheduledEvent e = event(EventStatus.SCHEDULED, null);
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(groupService.createGroup(any(), eq(host))).thenReturn(ChatResponse.builder().id(ROOM_UUID).build());
            when(eventRsvpRepository.findByEventAndStatusIn(eq(e), any())).thenReturn(List.of());

            assertThat(worker.startEvent(10L)).isTrue();
            assertThat(e.getStatus()).isEqualTo(EventStatus.LIVE);
        }

        @Test
        @DisplayName("host absent from repo → falls back to the event's host reference")
        void hostFallback() {
            ScheduledEvent e = event(EventStatus.SCHEDULED, null);
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.empty());
            when(groupService.createGroup(any(), eq(host))).thenReturn(ChatResponse.builder().id(ROOM_UUID).build());
            when(eventRsvpRepository.findByEventAndStatusIn(eq(e), any())).thenReturn(List.of());

            assertThat(worker.startEvent(10L)).isTrue();
            verify(groupService).createGroup(any(), eq(host));
        }

        @Test
        @DisplayName("a single notify failure does not abort the fan-out or the transition")
        void notifyFailureSwallowed() {
            ScheduledEvent e = event(EventStatus.SCHEDULED, null);
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(groupService.createGroup(any(), eq(host))).thenReturn(ChatResponse.builder().id(ROOM_UUID).build());
            User other = User.builder().username("other").build();
            other.setId(2L);
            EventRsvp r1 = EventRsvp.builder().event(e).user(host).status(RsvpStatus.GOING).build();
            EventRsvp r2 = EventRsvp.builder().event(e).user(other).status(RsvpStatus.INTERESTED).build();
            when(eventRsvpRepository.findByEventAndStatusIn(eq(e), any())).thenReturn(List.of(r1, r2));
            Mockito.doThrow(new RuntimeException("push down"))
                    .when(notificationService).createNotification(eq(host), anyString(), anyString(), anyString(), anyString());

            boolean started = worker.startEvent(10L);

            assertThat(started).isTrue();
            // Both recipients attempted despite the first throwing.
            verify(notificationService, times(2)).createNotification(any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("event vanished → false, nothing created")
        void notFound() {
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.empty());

            assertThat(worker.startEvent(10L)).isFalse();
            verify(groupService, never()).createGroup(any(), any());
            verify(scheduledEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("no longer SCHEDULED (e.g. cancelled) → false, nothing created")
        void notScheduled() {
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(event(EventStatus.CANCELLED, null)));

            assertThat(worker.startEvent(10L)).isFalse();
            verify(groupService, never()).createGroup(any(), any());
        }

        @Test
        @DisplayName("close time already elapsed → retire straight to ENDED, no room, returns false")
        void pastEndRetires() {
            ScheduledEvent e = event(EventStatus.SCHEDULED, Instant.now().minus(1, ChronoUnit.MINUTES));
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));

            boolean started = worker.startEvent(10L);

            assertThat(started).isFalse();
            assertThat(e.getStatus()).isEqualTo(EventStatus.ENDED);
            verify(scheduledEventRepository).save(e);
            verify(groupService, never()).createGroup(any(), any());
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("endEvent")
    class EndEvent {

        @Test
        @DisplayName("LIVE → ENDED, saved, returns true")
        void endsLive() {
            ScheduledEvent e = event(EventStatus.LIVE, Instant.now().minus(1, ChronoUnit.MINUTES));
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(e));

            assertThat(worker.endEvent(10L)).isTrue();
            assertThat(e.getStatus()).isEqualTo(EventStatus.ENDED);
            verify(scheduledEventRepository).save(e);
        }

        @Test
        @DisplayName("event vanished → false")
        void notFound() {
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.empty());
            assertThat(worker.endEvent(10L)).isFalse();
            verify(scheduledEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("not LIVE (already ENDED) → false")
        void notLive() {
            when(scheduledEventRepository.findById(10L)).thenReturn(Optional.of(event(EventStatus.ENDED, null)));
            assertThat(worker.endEvent(10L)).isFalse();
            verify(scheduledEventRepository, never()).save(any());
        }
    }
}
