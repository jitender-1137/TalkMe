package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ListenerShift;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ListenerShiftResponse;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.JoinPolicy;
import com.chat.talkMe.enums.ListenerReason;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.RoomMode;
import com.chat.talkMe.enums.ShiftStatus;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.ListenerShiftRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.ReputationRecorder;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ListenerServiceImpl} — the volunteer listener queue for
 * "Someone Is Listening" (features #26/#27).
 *
 * <p>Invariants exercised: guest gate on clock-on (TM_997); DB owns fair-queue ordering while the
 * {@code listeners:available} Redis set is a fully fail-open mirror; a request spins up a locked-down
 * LISTENING room (PRIVATE / INVITE_ONLY) and engages the oldest AVAILABLE listener; help is only
 * credited on a genuinely engaged session and trends the listener toward Great Listener at the
 * threshold via the reputation ledger.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListenerServiceImpl (unit)")
class ListenerServiceImplTest {

    private static final String AVAILABLE_SET = "listeners:available";

    @Mock private ListenerShiftRepository shiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private GroupService groupService;
    @Mock private ReputationRecorder reputationRecorder;
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private ListenerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ListenerServiceImpl(shiftRepository, userRepository, chatRepository,
                groupService, reputationRecorder, redis, messagingTemplate);

        // Redis is a fail-open mirror touched by most write paths — shared lenient stubs.
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        // save echoes back the entity, assigning a uuid the way JPA would on persist.
        lenient().when(shiftRepository.save(any(ListenerShift.class))).thenAnswer(inv -> {
            ListenerShift s = inv.getArgument(0);
            if (s.getUuid() == null) s.setUuid(UUID.randomUUID());
            return s;
        });
    }

    private User user(long id, String username, boolean guest) {
        User u = User.builder()
                .username(username).name("Name-" + username).isGuest(guest)
                .profileImage("avatar-" + username + ".png")
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private ListenerShift shift(User listener, ShiftStatus status) {
        ListenerShift s = ListenerShift.builder()
                .listener(listener).status(status)
                .startedAt(Instant.now()).peopleHelped(0)
                .build();
        s.setUuid(UUID.randomUUID());
        return s;
    }

    @Nested
    @DisplayName("goAvailable")
    class GoAvailable {

        @Test
        @DisplayName("guest → ForbiddenException TM_997, nothing persisted")
        void guestRejected() {
            User guest = user(1L, "ghost", true);

            assertThatThrownBy(() -> service.goAvailable(guest))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_997"));

            verify(shiftRepository, never()).save(any());
            verify(setOps, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("no live shift → creates an AVAILABLE shift, saves, mirrors to Redis set")
        void createsFreshShift() {
            User listener = user(2L, "alice", false);
            when(userRepository.findById(2L)).thenReturn(Optional.of(listener));
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.empty());

            ListenerShiftResponse res = service.goAvailable(listener);

            ArgumentCaptor<ListenerShift> cap = ArgumentCaptor.forClass(ListenerShift.class);
            verify(shiftRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(ShiftStatus.AVAILABLE);
            assertThat(cap.getValue().getPeopleHelped()).isZero();
            assertThat(cap.getValue().getStartedAt()).isNotNull();
            verify(setOps).add(AVAILABLE_SET, "alice");
            assertThat(res.getStatus()).isEqualTo("AVAILABLE");
            assertThat(res.getListenerUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("existing live shift → re-armed to AVAILABLE and room binding cleared")
        void reArmsExistingShift() {
            User listener = user(3L, "bob", false);
            ListenerShift live = shift(listener, ShiftStatus.ENGAGED);
            live.setRoomChatUuid("room-xyz");
            when(userRepository.findById(3L)).thenReturn(Optional.of(listener));
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(live));

            ListenerShiftResponse res = service.goAvailable(listener);

            assertThat(live.getStatus()).isEqualTo(ShiftStatus.AVAILABLE);
            assertThat(live.getRoomChatUuid()).isNull();
            verify(shiftRepository).save(live);
            verify(setOps).add(AVAILABLE_SET, "bob");
            assertThat(res.getStatus()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("user not resolvable from DB → falls back to the passed principal")
        void fallsBackToPrincipalWhenNotInDb() {
            User listener = user(4L, "carol", false);
            when(userRepository.findById(4L)).thenReturn(Optional.empty());
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.empty());

            ListenerShiftResponse res = service.goAvailable(listener);

            verify(setOps).add(AVAILABLE_SET, "carol");
            assertThat(res.getListenerUsername()).isEqualTo("carol");
        }

        @Test
        @DisplayName("Redis add failure is swallowed (fail-open) — shift still persisted")
        void redisFailureIsFailOpen() {
            User listener = user(5L, "dave", false);
            when(userRepository.findById(5L)).thenReturn(Optional.of(listener));
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.empty());
            when(redis.opsForSet()).thenThrow(new RuntimeException("redis down"));

            ListenerShiftResponse res = service.goAvailable(listener);

            assertThat(res.getStatus()).isEqualTo("AVAILABLE");
            verify(shiftRepository).save(any());
        }
    }

    @Nested
    @DisplayName("endShift")
    class EndShift {

        @Test
        @DisplayName("no live shift → idempotent no-op but still scrubs the Redis mirror")
        void noLiveShiftIsIdempotent() {
            User listener = user(6L, "erin", false);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.empty());

            service.endShift(listener);

            verify(setOps).remove(AVAILABLE_SET, "erin");
            verify(shiftRepository, never()).save(any());
        }

        @Test
        @DisplayName("AVAILABLE shift → ended without crediting help")
        void endsAvailableShiftWithoutCredit() {
            User listener = user(7L, "finn", false);
            ListenerShift s = shift(listener, ShiftStatus.AVAILABLE);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.endShift(listener);

            assertThat(s.getStatus()).isEqualTo(ShiftStatus.ENDED);
            assertThat(s.getEndedAt()).isNotNull();
            assertThat(s.getPeopleHelped()).isZero();
            verify(setOps).remove(AVAILABLE_SET, "finn");
            verify(shiftRepository).save(s);
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("ENGAGED shift → credits the in-progress help then ends")
        void engagedShiftCreditsThenEnds() {
            User listener = user(8L, "gwen", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setPeopleHelped(0);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.endShift(listener);

            assertThat(s.getPeopleHelped()).isEqualTo(1);
            assertThat(s.getStatus()).isEqualTo(ShiftStatus.ENDED);
            verify(reputationRecorder, never()).record(any(), any(), any()); // below threshold
        }

        @Test
        @DisplayName("ENGAGED shift crossing the help threshold → records the Great Listener trend")
        void engagedShiftAtThresholdRecordsReputation() {
            User listener = user(9L, "hank", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setPeopleHelped(2); // → 3, the GREAT_LISTENER_THRESHOLD
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.endShift(listener);

            assertThat(s.getPeopleHelped()).isEqualTo(3);
            verify(reputationRecorder).record(eq(9L), eq(ReputationEventType.EVENT_ATTENDED),
                    eq("listener_great:" + s.getUuid()));
        }

        @Test
        @DisplayName("reputation ledger failure is swallowed — shift still ends cleanly")
        void reputationFailureSwallowed() {
            User listener = user(10L, "ivy", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setPeopleHelped(2);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));
            Mockito.doThrow(new RuntimeException("ledger down"))
                    .when(reputationRecorder).record(any(), any(), any());

            service.endShift(listener);

            assertThat(s.getStatus()).isEqualTo(ShiftStatus.ENDED);
            verify(shiftRepository).save(s);
        }
    }

    @Nested
    @DisplayName("requestListener")
    class RequestListener {

        private final String roomId = UUID.randomUUID().toString();

        private void stubMatch(User seeker, ListenerShift matched) {
            when(userRepository.findById(seeker.getId())).thenReturn(Optional.of(seeker));
            when(shiftRepository.findFirstByStatusAndListenerNotOrderByStartedAtAsc(ShiftStatus.AVAILABLE, seeker))
                    .thenReturn(Optional.of(matched));
            ChatResponse room = ChatResponse.builder().id(roomId).build();
            when(groupService.createGroup(any(CreateGroupRequest.class), eq(matched.getListener())))
                    .thenReturn(room);
            when(chatRepository.findByUuid(UUID.fromString(roomId)))
                    .thenReturn(Optional.of(new Chat()));
        }

        @Test
        @DisplayName("no listener available → NotFoundException TM_993")
        void noListenerAvailable() {
            User seeker = user(20L, "seeker", false);
            when(userRepository.findById(20L)).thenReturn(Optional.of(seeker));
            when(shiftRepository.findFirstByStatusAndListenerNotOrderByStartedAtAsc(ShiftStatus.AVAILABLE, seeker))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestListener(seeker, ListenerReason.NEED_TO_TALK))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_993"));

            verify(groupService, never()).createGroup(any(), any());
        }

        @Test
        @DisplayName("nominal match → creates room, admits seeker, locks room down, engages shift")
        void nominalMatch() {
            User seeker = user(21L, "seeker", false);
            User listener = user(22L, "listener", false);
            ListenerShift matched = shift(listener, ShiftStatus.AVAILABLE);
            stubMatch(seeker, matched);
            Chat chat = new Chat();
            when(chatRepository.findByUuid(UUID.fromString(roomId))).thenReturn(Optional.of(chat));

            ListenerShiftResponse res = service.requestListener(seeker, ListenerReason.CANT_SLEEP);

            // Room admits the seeker while briefly open, then is locked to PRIVATE/INVITE_ONLY.
            verify(groupService).joinChat(roomId, seeker);
            assertThat(chat.getRoomMode()).isEqualTo(RoomMode.LISTENING);
            assertThat(chat.getVisibility()).isEqualTo(ChatVisibility.PRIVATE);
            assertThat(chat.getJoinPolicy()).isEqualTo(JoinPolicy.INVITE_ONLY);
            verify(chatRepository).save(chat);
            // Shift bound to the room and engaged.
            assertThat(matched.getStatus()).isEqualTo(ShiftStatus.ENGAGED);
            assertThat(matched.getRoomChatUuid()).isEqualTo(roomId);
            verify(shiftRepository).save(matched);
            // Listener removed from the availability mirror + nudged over WS.
            verify(setOps).remove(AVAILABLE_SET, "listener");
            verify(messagingTemplate).convertAndSend(eq("/topic/listener/listener"), any(Object.class));
            assertThat(res.getStatus()).isEqualTo("ENGAGED");
            assertThat(res.getRoomChatUuid()).isEqualTo(roomId);
        }

        @Test
        @DisplayName("null reason → defaults to NEED_TO_TALK in the room title")
        void nullReasonDefaults() {
            User seeker = user(23L, "seeker", false);
            User listener = user(24L, "listener", false);
            ListenerShift matched = shift(listener, ShiftStatus.AVAILABLE);
            stubMatch(seeker, matched);

            service.requestListener(seeker, null);

            ArgumentCaptor<CreateGroupRequest> cap = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(cap.capture(), eq(listener));
            assertThat(cap.getValue().getName()).contains(ListenerReason.NEED_TO_TALK.getLabel());
            assertThat(cap.getValue().getSubtype()).isEqualTo("room");
            assertThat(cap.getValue().getVisibility()).isEqualTo("PUBLIC");
        }

        @Test
        @DisplayName("room vanished after create → NotFoundException TM_998")
        void roomNotFoundAfterCreate() {
            User seeker = user(25L, "seeker", false);
            User listener = user(26L, "listener", false);
            ListenerShift matched = shift(listener, ShiftStatus.AVAILABLE);
            when(userRepository.findById(25L)).thenReturn(Optional.of(seeker));
            when(shiftRepository.findFirstByStatusAndListenerNotOrderByStartedAtAsc(ShiftStatus.AVAILABLE, seeker))
                    .thenReturn(Optional.of(matched));
            ChatResponse room = ChatResponse.builder().id(roomId).build();
            when(groupService.createGroup(any(CreateGroupRequest.class), eq(listener))).thenReturn(room);
            when(chatRepository.findByUuid(UUID.fromString(roomId))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestListener(seeker, ListenerReason.LONELY))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_998"));

            verify(shiftRepository, never()).save(matched);
        }
    }

    @Nested
    @DisplayName("completeShift")
    class CompleteShift {

        @Test
        @DisplayName("no active shift → NotFoundException TM_996")
        void noActiveShift() {
            User listener = user(30L, "listener", false);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeShift(listener))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_996"));
        }

        @Test
        @DisplayName("ENGAGED with a bound room → credits help, re-arms to AVAILABLE")
        void engagedWithRoomCreditsAndReArms() {
            User listener = user(31L, "listener", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setRoomChatUuid("room-1");
            s.setPeopleHelped(0);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            ListenerShiftResponse res = service.completeShift(listener);

            assertThat(s.getPeopleHelped()).isEqualTo(1);
            assertThat(s.getStatus()).isEqualTo(ShiftStatus.AVAILABLE);
            assertThat(s.getRoomChatUuid()).isNull();
            verify(shiftRepository).save(s);
            verify(setOps).add(AVAILABLE_SET, "listener");
            assertThat(res.getStatus()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("ENGAGED but no bound room → no help credited (anti-farm guard)")
        void engagedWithoutRoomDoesNotCredit() {
            User listener = user(32L, "listener", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setRoomChatUuid(null);
            s.setPeopleHelped(0);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.completeShift(listener);

            assertThat(s.getPeopleHelped()).isZero();
            assertThat(s.getStatus()).isEqualTo(ShiftStatus.AVAILABLE);
            verify(setOps).add(AVAILABLE_SET, "listener");
        }

        @Test
        @DisplayName("AVAILABLE (idle) shift → no help credited")
        void availableShiftDoesNotCredit() {
            User listener = user(33L, "listener", false);
            ListenerShift s = shift(listener, ShiftStatus.AVAILABLE);
            s.setPeopleHelped(0);
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.completeShift(listener);

            assertThat(s.getPeopleHelped()).isZero();
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("completing a session at the help threshold → records the Great Listener trend")
        void completeAtThresholdRecordsReputation() {
            User listener = user(34L, "listener", false);
            ListenerShift s = shift(listener, ShiftStatus.ENGAGED);
            s.setRoomChatUuid("room-1");
            s.setPeopleHelped(2); // → 3
            when(shiftRepository.findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED))
                    .thenReturn(Optional.of(s));

            service.completeShift(listener);

            verify(reputationRecorder).record(eq(34L), eq(ReputationEventType.EVENT_ATTENDED),
                    eq("listener_great:" + s.getUuid()));
        }
    }

    @Nested
    @DisplayName("listAvailable")
    class ListAvailable {

        @Test
        @DisplayName("no available listeners → empty list, no NPE")
        void empty() {
            when(shiftRepository.findByStatusOrderByStartedAtAsc(ShiftStatus.AVAILABLE))
                    .thenReturn(List.of());

            assertThat(service.listAvailable()).isEmpty();
        }

        @Test
        @DisplayName("maps every available shift to a response, oldest first")
        void mapsAll() {
            ListenerShift s1 = shift(user(40L, "a", false), ShiftStatus.AVAILABLE);
            ListenerShift s2 = shift(user(41L, "b", false), ShiftStatus.AVAILABLE);
            when(shiftRepository.findByStatusOrderByStartedAtAsc(ShiftStatus.AVAILABLE))
                    .thenReturn(List.of(s1, s2));

            List<ListenerShiftResponse> out = service.listAvailable();

            assertThat(out).hasSize(2);
            assertThat(out).extracting(ListenerShiftResponse::getListenerUsername)
                    .containsExactly("a", "b");
            assertThat(out).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo("AVAILABLE"));
            verify(shiftRepository, times(1)).findByStatusOrderByStartedAtAsc(ShiftStatus.AVAILABLE);
        }
    }
}
