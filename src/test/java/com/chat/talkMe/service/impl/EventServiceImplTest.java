package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.EventRsvp;
import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateEventRequest;
import com.chat.talkMe.dto.response.EventResponse;
import com.chat.talkMe.enums.EventStatus;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.RsvpStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.EventRsvpRepository;
import com.chat.talkMe.repository.ScheduledEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ReputationRecorder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link EventServiceImpl} — the "Midnight Events" (feature #24)
 * service. Every collaborator is mocked and the SUT is built by hand (no {@code @InjectMocks}).
 *
 * <p>Covers create validation (TM_957), RSVP flow incl. seat-cap (TM_958/959/960), cancel
 * ownership/lifecycle (TM_956/961), the load-event UUID/not-found path (TM_955), attendance
 * crediting (walk-in vs RSVP'd vs already-attended, best-effort reputation), and the two
 * orchestrator sweeps (start/end due events, partial-failure isolation).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventServiceImpl (unit)")
class EventServiceImplTest {

    private static final UUID EVENT_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String EVENT_UUID_STR = EVENT_UUID.toString();
    private static final UUID ROOM_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String ROOM_UUID_STR = ROOM_UUID.toString();

    @Mock private ScheduledEventRepository scheduledEventRepository;
    @Mock private EventRsvpRepository eventRsvpRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatMemberRepository chatMemberRepository;
    @Mock private ReputationRecorder reputationRecorder;
    @Mock private EventTransitionWorker transitionWorker;

    private EventServiceImpl service;

    private User host;

    @BeforeEach
    void setUp() {
        service = new EventServiceImpl(scheduledEventRepository, eventRsvpRepository, userRepository,
                chatRepository, chatMemberRepository, reputationRecorder, transitionWorker);

        host = User.builder().username("host").name("Host").email("h@e.com").profileImage("h.png").build();
        host.setId(1L);
        host.setUuid(UUID.fromString("cccccccc-0000-0000-0000-000000000009"));
    }

    private ScheduledEvent event(EventStatus status) {
        ScheduledEvent e = ScheduledEvent.builder()
                .host(host)
                .title("Late Night Jam")
                .description("music")
                .startAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .category("music")
                .maxAttendees(0)
                .status(status)
                .build();
        e.setId(10L);
        e.setUuid(EVENT_UUID);
        return e;
    }

    /** Default counts used by the private toResponse mapper so it never NPEs. */
    private void stubResponseCounts() {
        lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(0L);
        lenient().when(eventRsvpRepository.findByEventAndUser(any(), any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("createEvent")
    class CreateEvent {

        private CreateEventRequest validRequest() {
            CreateEventRequest r = new CreateEventRequest();
            r.setTitle("  Late Night Jam  ");
            r.setDescription("music");
            r.setStartAt(Instant.now().plus(2, ChronoUnit.HOURS));
            r.setCategory("music");
            r.setMaxAttendees(10);
            return r;
        }

        @Test
        @DisplayName("nominal → persists a SCHEDULED event with trimmed title and returns its response")
        void nominalSuccess() {
            CreateEventRequest r = validRequest();
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(scheduledEventRepository.save(any(ScheduledEvent.class))).thenAnswer(inv -> {
                ScheduledEvent e = inv.getArgument(0);
                e.setUuid(EVENT_UUID);
                return e;
            });
            stubResponseCounts();

            EventResponse resp = service.createEvent(r, host);

            ArgumentCaptor<ScheduledEvent> saved = ArgumentCaptor.forClass(ScheduledEvent.class);
            verify(scheduledEventRepository).save(saved.capture());
            ScheduledEvent e = saved.getValue();
            assertThat(e.getTitle()).isEqualTo("Late Night Jam"); // trimmed
            assertThat(e.getStatus()).isEqualTo(EventStatus.SCHEDULED);
            assertThat(e.isReminderSent()).isFalse();
            assertThat(e.getMaxAttendees()).isEqualTo(10);
            assertThat(e.getHost()).isEqualTo(host);
            assertThat(resp.getStatus()).isEqualTo("SCHEDULED");
            assertThat(resp.isHostedByMe()).isTrue();
        }

        @Test
        @DisplayName("host absent from repo → falls back to the passed-in host")
        void hostFallback() {
            CreateEventRequest r = validRequest();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());
            when(scheduledEventRepository.save(any(ScheduledEvent.class))).thenAnswer(inv -> {
                ScheduledEvent e = inv.getArgument(0);
                e.setUuid(EVENT_UUID);
                return e;
            });
            stubResponseCounts();

            service.createEvent(r, host);

            ArgumentCaptor<ScheduledEvent> saved = ArgumentCaptor.forClass(ScheduledEvent.class);
            verify(scheduledEventRepository).save(saved.capture());
            assertThat(saved.getValue().getHost()).isEqualTo(host);
        }

        @Test
        @DisplayName("maxAttendees 0 (unlimited) is accepted and stored as 0")
        void maxAttendeesZeroAllowed() {
            CreateEventRequest r = validRequest();
            r.setMaxAttendees(0);
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(scheduledEventRepository.save(any(ScheduledEvent.class))).thenAnswer(inv -> {
                ScheduledEvent e = inv.getArgument(0);
                e.setUuid(EVENT_UUID);
                return e;
            });
            stubResponseCounts();

            service.createEvent(r, host);

            ArgumentCaptor<ScheduledEvent> saved = ArgumentCaptor.forClass(ScheduledEvent.class);
            verify(scheduledEventRepository).save(saved.capture());
            assertThat(saved.getValue().getMaxAttendees()).isEqualTo(0);
        }

        @Test
        @DisplayName("valid endAt after startAt is stored")
        void endAtStored() {
            CreateEventRequest r = validRequest();
            Instant end = r.getStartAt().plus(1, ChronoUnit.HOURS);
            r.setEndAt(end);
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(scheduledEventRepository.save(any(ScheduledEvent.class))).thenAnswer(inv -> {
                ScheduledEvent e = inv.getArgument(0);
                e.setUuid(EVENT_UUID);
                return e;
            });
            stubResponseCounts();

            service.createEvent(r, host);

            ArgumentCaptor<ScheduledEvent> saved = ArgumentCaptor.forClass(ScheduledEvent.class);
            verify(scheduledEventRepository).save(saved.capture());
            assertThat(saved.getValue().getEndAt()).isEqualTo(end);
        }

        @Test
        @DisplayName("null startAt → BadRequest TM_957")
        void nullStart() {
            CreateEventRequest r = validRequest();
            r.setStartAt(null);
            assertThatThrownBy(() -> service.createEvent(r, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_957"));
            verify(scheduledEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("startAt in the past → BadRequest TM_957")
        void pastStart() {
            CreateEventRequest r = validRequest();
            r.setStartAt(Instant.now().minus(1, ChronoUnit.HOURS));
            assertThatThrownBy(() -> service.createEvent(r, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_957"));
        }

        @Test
        @DisplayName("endAt not after startAt → BadRequest TM_957")
        void endBeforeStart() {
            CreateEventRequest r = validRequest();
            r.setEndAt(r.getStartAt().minus(1, ChronoUnit.MINUTES));
            assertThatThrownBy(() -> service.createEvent(r, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_957"));
        }

        @Test
        @DisplayName("negative maxAttendees → BadRequest TM_957")
        void negativeMax() {
            CreateEventRequest r = validRequest();
            r.setMaxAttendees(-1);
            assertThatThrownBy(() -> service.createEvent(r, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_957"));
        }
    }

    @Nested
    @DisplayName("listUpcoming")
    class ListUpcoming {

        @Test
        @DisplayName("maps every upcoming SCHEDULED event")
        void mapsUpcoming() {
            when(scheduledEventRepository.findByStatusAndStartAtAfterOrderByStartAtAsc(eq(EventStatus.SCHEDULED), any()))
                    .thenReturn(List.of(event(EventStatus.SCHEDULED)));
            stubResponseCounts();

            List<EventResponse> list = service.listUpcoming(host);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getEventUuid()).isEqualTo(EVENT_UUID_STR);
        }

        @Test
        @DisplayName("no upcoming events → empty list, no NPE")
        void empty() {
            when(scheduledEventRepository.findByStatusAndStartAtAfterOrderByStartAtAsc(eq(EventStatus.SCHEDULED), any()))
                    .thenReturn(List.of());

            assertThat(service.listUpcoming(host)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getEvent")
    class GetEvent {

        @Test
        @DisplayName("existing event → mapped response")
        void found() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(event(EventStatus.LIVE)));
            stubResponseCounts();

            EventResponse resp = service.getEvent(EVENT_UUID_STR, host);

            assertThat(resp.getStatus()).isEqualTo("LIVE");
        }

        @Test
        @DisplayName("malformed uuid → NotFound TM_955")
        void malformedUuid() {
            assertThatThrownBy(() -> service.getEvent("not-a-uuid", host))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_955"));
        }

        @Test
        @DisplayName("unknown uuid → NotFound TM_955")
        void notFound() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getEvent(EVENT_UUID_STR, host))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_955"));
        }
    }

    @Nested
    @DisplayName("rsvp")
    class Rsvp {

        @Test
        @DisplayName("new RSVP under an unlimited cap → persists GOING, attended=false")
        void newGoing() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            stubResponseCounts(); // broad findByEventAndUser → empty (no existing RSVP)

            service.rsvp(host, EVENT_UUID_STR, "going");

            ArgumentCaptor<EventRsvp> saved = ArgumentCaptor.forClass(EventRsvp.class);
            verify(eventRsvpRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(RsvpStatus.GOING);
            assertThat(saved.getValue().isAttended()).isFalse();
            assertThat(saved.getValue().getUser()).isEqualTo(host);
        }

        @Test
        @DisplayName("existing RSVP → updates the same row's status")
        void updatesExisting() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            EventRsvp existing = EventRsvp.builder().event(e).user(host).status(RsvpStatus.INTERESTED).build();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.of(existing));
            lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(0L);

            service.rsvp(host, EVENT_UUID_STR, "DECLINED");

            verify(eventRsvpRepository).save(existing);
            assertThat(existing.getStatus()).isEqualTo(RsvpStatus.DECLINED);
        }

        @Test
        @DisplayName("INTERESTED never consumes a seat — saved even when GOING is at capacity")
        void interestedSkipsCap() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            e.setMaxAttendees(1);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.empty());
            // Full on GOING — an INTERESTED RSVP must still succeed (cap only guards GOING).
            lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(1L);

            service.rsvp(host, EVENT_UUID_STR, "INTERESTED");

            ArgumentCaptor<EventRsvp> saved = ArgumentCaptor.forClass(EventRsvp.class);
            verify(eventRsvpRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(RsvpStatus.INTERESTED);
        }

        @Test
        @DisplayName("already GOING re-RSVPs GOING at capacity → not rejected, same row re-saved")
        void alreadyGoingSkipsCap() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            e.setMaxAttendees(1);
            EventRsvp existing = EventRsvp.builder().event(e).user(host).status(RsvpStatus.GOING).build();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.of(existing));
            // At capacity — but the seat is already theirs, so no TM_959.
            lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(1L);

            service.rsvp(host, EVENT_UUID_STR, "GOING");

            verify(eventRsvpRepository).save(existing);
            assertThat(existing.getStatus()).isEqualTo(RsvpStatus.GOING);
        }

        @Test
        @DisplayName("new GOING with free seats under the cap → allowed")
        void goingUnderCap() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            e.setMaxAttendees(5);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.empty());
            when(eventRsvpRepository.countByEventAndStatus(e, RsvpStatus.GOING)).thenReturn(2L);
            lenient().when(eventRsvpRepository.countByEventAndStatus(e, RsvpStatus.INTERESTED)).thenReturn(0L);

            service.rsvp(host, EVENT_UUID_STR, "GOING");

            verify(eventRsvpRepository).save(any(EventRsvp.class));
        }

        @Test
        @DisplayName("null status → BadRequest TM_960")
        void nullStatus() {
            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_960"));
            verify(eventRsvpRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown status token → BadRequest TM_960")
        void invalidStatus() {
            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, "MAYBE"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_960"));
        }

        @Test
        @DisplayName("event CANCELLED → BadRequest TM_958")
        void cancelledClosed() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(event(EventStatus.CANCELLED)));
            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, "GOING"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_958"));
        }

        @Test
        @DisplayName("event ENDED → BadRequest TM_958")
        void endedClosed() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(event(EventStatus.ENDED)));
            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, "GOING"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_958"));
        }

        @Test
        @DisplayName("new GOING at capacity → BadRequest TM_959, nothing saved")
        void full() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            e.setMaxAttendees(2);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.empty());
            when(eventRsvpRepository.countByEventAndStatus(e, RsvpStatus.GOING)).thenReturn(2L);

            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, "GOING"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_959"));
            verify(eventRsvpRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown event → NotFound TM_955")
        void eventNotFound() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.rsvp(host, EVENT_UUID_STR, "GOING"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_955"));
        }
    }

    @Nested
    @DisplayName("cancelEvent")
    class CancelEvent {

        @Test
        @DisplayName("host cancels a SCHEDULED event → status CANCELLED, saved")
        void cancelScheduled() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            stubResponseCounts();

            EventResponse resp = service.cancelEvent(EVENT_UUID_STR, host);

            verify(scheduledEventRepository).save(e);
            assertThat(e.getStatus()).isEqualTo(EventStatus.CANCELLED);
            assertThat(resp.getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("host cancels a LIVE event → allowed")
        void cancelLive() {
            ScheduledEvent e = event(EventStatus.LIVE);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            stubResponseCounts();

            service.cancelEvent(EVENT_UUID_STR, host);

            assertThat(e.getStatus()).isEqualTo(EventStatus.CANCELLED);
        }

        @Test
        @DisplayName("non-host → Forbidden TM_956")
        void notHost() {
            ScheduledEvent e = event(EventStatus.SCHEDULED);
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            User other = User.builder().username("other").build();
            other.setId(99L);

            assertThatThrownBy(() -> service.cancelEvent(EVENT_UUID_STR, other))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_956"));
            verify(scheduledEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("already ENDED → BadRequest TM_961")
        void alreadyEnded() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(event(EventStatus.ENDED)));
            assertThatThrownBy(() -> service.cancelEvent(EVENT_UUID_STR, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_961"));
        }

        @Test
        @DisplayName("already CANCELLED → BadRequest TM_961")
        void alreadyCancelled() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(event(EventStatus.CANCELLED)));
            assertThatThrownBy(() -> service.cancelEvent(EVENT_UUID_STR, host))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_961"));
        }

        @Test
        @DisplayName("unknown event → NotFound TM_955")
        void notFound() {
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.cancelEvent(EVENT_UUID_STR, host))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_955"));
        }
    }

    @Nested
    @DisplayName("markAttended")
    class MarkAttended {

        private ScheduledEvent liveWithRoom() {
            ScheduledEvent e = event(EventStatus.LIVE);
            e.setRoomChatUuid(ROOM_UUID_STR);
            return e;
        }

        private void memberOfRoom() {
            Chat room = Chat.builder().build();
            room.setUuid(ROOM_UUID);
            when(chatRepository.findByUuid(ROOM_UUID)).thenReturn(Optional.of(room));
            when(chatMemberRepository.findByChatAndUser(room, host))
                    .thenReturn(Optional.of(new ChatMember()));
        }

        @Test
        @DisplayName("event has no room yet → returns response, records nothing")
        void noRoom() {
            ScheduledEvent e = event(EventStatus.LIVE); // roomChatUuid null
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            stubResponseCounts();

            service.markAttended(EVENT_UUID_STR, host);

            verify(eventRsvpRepository, never()).save(any());
            verify(reputationRecorder, never()).record(anyLong(), any(), any());
        }

        @Test
        @DisplayName("user is not a member of the room → returns response, records nothing")
        void notRoomMember() {
            ScheduledEvent e = liveWithRoom();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            Chat room = Chat.builder().build();
            room.setUuid(ROOM_UUID);
            when(chatRepository.findByUuid(ROOM_UUID)).thenReturn(Optional.of(room));
            when(chatMemberRepository.findByChatAndUser(room, host)).thenReturn(Optional.empty());
            stubResponseCounts();

            service.markAttended(EVENT_UUID_STR, host);

            verify(eventRsvpRepository, never()).save(any());
            verify(reputationRecorder, never()).record(anyLong(), any(), any());
        }

        @Test
        @DisplayName("walk-in member (no RSVP) → creates GOING+attended RSVP and credits reputation once")
        void walkIn() {
            ScheduledEvent e = liveWithRoom();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            memberOfRoom();
            stubResponseCounts(); // broad findByEventAndUser → empty (no prior RSVP → walk-in)

            service.markAttended(EVENT_UUID_STR, host);

            ArgumentCaptor<EventRsvp> saved = ArgumentCaptor.forClass(EventRsvp.class);
            verify(eventRsvpRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(RsvpStatus.GOING);
            assertThat(saved.getValue().isAttended()).isTrue();
            verify(reputationRecorder).record(1L, ReputationEventType.EVENT_ATTENDED,
                    EVENT_UUID_STR + ":" + 1L);
        }

        @Test
        @DisplayName("RSVP'd but not yet attended → flips attended and credits reputation once")
        void firstAttendanceOnExistingRsvp() {
            ScheduledEvent e = liveWithRoom();
            EventRsvp rsvp = EventRsvp.builder().event(e).user(host).status(RsvpStatus.GOING).attended(false).build();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            memberOfRoom();
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.of(rsvp));
            lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(0L);

            service.markAttended(EVENT_UUID_STR, host);

            assertThat(rsvp.isAttended()).isTrue();
            verify(eventRsvpRepository).save(rsvp);
            verify(reputationRecorder).record(1L, ReputationEventType.EVENT_ATTENDED, EVENT_UUID_STR + ":" + 1L);
        }

        @Test
        @DisplayName("already attended → re-saves but does NOT re-credit reputation")
        void alreadyAttended() {
            ScheduledEvent e = liveWithRoom();
            EventRsvp rsvp = EventRsvp.builder().event(e).user(host).status(RsvpStatus.GOING).attended(true).build();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            memberOfRoom();
            when(eventRsvpRepository.findByEventAndUser(e, host)).thenReturn(Optional.of(rsvp));
            lenient().when(eventRsvpRepository.countByEventAndStatus(any(), any())).thenReturn(0L);

            service.markAttended(EVENT_UUID_STR, host);

            verify(eventRsvpRepository).save(rsvp);
            verify(reputationRecorder, never()).record(anyLong(), any(), any());
        }

        @Test
        @DisplayName("reputation recorder blowing up is swallowed — attendance still succeeds")
        void reputationFailureSwallowed() {
            ScheduledEvent e = liveWithRoom();
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            memberOfRoom();
            Mockito.doThrow(new RuntimeException("ledger down"))
                    .when(reputationRecorder).record(anyLong(), any(), any());
            stubResponseCounts(); // broad findByEventAndUser → empty (walk-in)

            EventResponse resp = service.markAttended(EVENT_UUID_STR, host);

            assertThat(resp).isNotNull();
            verify(eventRsvpRepository).save(any(EventRsvp.class));
        }
    }

    @Nested
    @DisplayName("markAttendedByRoom")
    class MarkAttendedByRoom {

        @Test
        @DisplayName("null room uuid → returns null, no lookup")
        void nullRoom() {
            assertThat(service.markAttendedByRoom(null, host)).isNull();
            verify(scheduledEventRepository, never()).findByRoomChatUuid(any());
        }

        @Test
        @DisplayName("blank room uuid → returns null")
        void blankRoom() {
            assertThat(service.markAttendedByRoom("   ", host)).isNull();
        }

        @Test
        @DisplayName("no event owns that room → returns null")
        void noEventForRoom() {
            when(scheduledEventRepository.findByRoomChatUuid(ROOM_UUID_STR)).thenReturn(Optional.empty());
            assertThat(service.markAttendedByRoom(ROOM_UUID_STR, host)).isNull();
        }

        @Test
        @DisplayName("event found → delegates to markAttended")
        void delegates() {
            ScheduledEvent e = event(EventStatus.LIVE); // no room set → markAttended returns response early
            when(scheduledEventRepository.findByRoomChatUuid(ROOM_UUID_STR)).thenReturn(Optional.of(e));
            when(scheduledEventRepository.findByUuid(EVENT_UUID)).thenReturn(Optional.of(e));
            when(userRepository.findById(1L)).thenReturn(Optional.of(host));
            stubResponseCounts();

            EventResponse resp = service.markAttendedByRoom(ROOM_UUID_STR, host);

            assertThat(resp).isNotNull();
        }
    }

    @Nested
    @DisplayName("startDueEvents")
    class StartDueEvents {

        @Test
        @DisplayName("counts only the events the worker actually transitioned")
        void countsStarted() {
            ScheduledEvent a = event(EventStatus.SCHEDULED);
            a.setId(1L);
            ScheduledEvent b = event(EventStatus.SCHEDULED);
            b.setId(2L);
            when(scheduledEventRepository.findByStatusAndStartAtLessThanEqual(eq(EventStatus.SCHEDULED), any()))
                    .thenReturn(List.of(a, b));
            when(transitionWorker.startEvent(1L)).thenReturn(true);
            when(transitionWorker.startEvent(2L)).thenReturn(false);

            assertThat(service.startDueEvents()).isEqualTo(1);
        }

        @Test
        @DisplayName("one worker failure does not abort the batch")
        void partialFailureIsolation() {
            ScheduledEvent a = event(EventStatus.SCHEDULED);
            a.setId(1L);
            ScheduledEvent b = event(EventStatus.SCHEDULED);
            b.setId(2L);
            when(scheduledEventRepository.findByStatusAndStartAtLessThanEqual(eq(EventStatus.SCHEDULED), any()))
                    .thenReturn(List.of(a, b));
            when(transitionWorker.startEvent(1L)).thenThrow(new RuntimeException("boom"));
            when(transitionWorker.startEvent(2L)).thenReturn(true);

            assertThat(service.startDueEvents()).isEqualTo(1);
            verify(transitionWorker, times(2)).startEvent(anyLong());
        }

        @Test
        @DisplayName("nothing due → 0")
        void nothingDue() {
            when(scheduledEventRepository.findByStatusAndStartAtLessThanEqual(eq(EventStatus.SCHEDULED), any()))
                    .thenReturn(List.of());
            assertThat(service.startDueEvents()).isZero();
        }
    }

    @Nested
    @DisplayName("endDueEvents")
    class EndDueEvents {

        @Test
        @DisplayName("counts only the events the worker actually ended")
        void countsEnded() {
            ScheduledEvent a = event(EventStatus.LIVE);
            a.setId(1L);
            when(scheduledEventRepository.findByStatusAndEndAtIsNotNullAndEndAtLessThanEqual(eq(EventStatus.LIVE), any()))
                    .thenReturn(List.of(a));
            when(transitionWorker.endEvent(1L)).thenReturn(true);

            assertThat(service.endDueEvents()).isEqualTo(1);
        }

        @Test
        @DisplayName("one worker failure does not abort the batch")
        void partialFailureIsolation() {
            ScheduledEvent a = event(EventStatus.LIVE);
            a.setId(1L);
            ScheduledEvent b = event(EventStatus.LIVE);
            b.setId(2L);
            when(scheduledEventRepository.findByStatusAndEndAtIsNotNullAndEndAtLessThanEqual(eq(EventStatus.LIVE), any()))
                    .thenReturn(List.of(a, b));
            when(transitionWorker.endEvent(1L)).thenThrow(new RuntimeException("boom"));
            when(transitionWorker.endEvent(2L)).thenReturn(true);

            assertThat(service.endDueEvents()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing due → 0")
        void nothingDue() {
            when(scheduledEventRepository.findByStatusAndEndAtIsNotNullAndEndAtLessThanEqual(eq(EventStatus.LIVE), any()))
                    .thenReturn(List.of());
            assertThat(service.endDueEvents()).isZero();
        }
    }
}
