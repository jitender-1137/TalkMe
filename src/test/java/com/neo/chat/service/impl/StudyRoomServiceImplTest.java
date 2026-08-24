package com.neo.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateGroupRequest;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.StudyRoomResponse;
import com.neo.chat.dto.response.StudySessionResponse;
import com.neo.chat.enums.PomodoroPhase;
import com.neo.chat.enums.RoomMode;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.StudyRoomChatRepository;
import com.neo.chat.service.GroupService;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link StudyRoomServiceImpl}.
 *
 * <p>Study rooms are public ROOMs flipped into {@link RoomMode#STUDY_POMODORO}; the shared Pomodoro
 * session (phase/deadline/focus-break/goals) lives in a Redis string and the per-room phase deadline
 * in a Redis ZSET reaped centrally. Redis ops are mocked via {@code template.opsForX()}; a real
 * {@link ObjectMapper} is used so the session JSON round-trips exactly as in production.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StudyRoomServiceImpl (unit)")
class StudyRoomServiceImplTest {

    private static final String SESSION_PREFIX = "study:session:";
    private static final String PRESENCE_PREFIX = "study:presence:";
    private static final String DEADLINE_ZSET = "study:pomodoro-deadlines";

    @Mock
    private GroupService groupService;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private StudyRoomChatRepository studyRoomChatRepository;
    @Mock
    private PresenceService presenceService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StudyRoomServiceImpl service;
    private User owner;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        lenient().when(setOps.members(anyString())).thenReturn(Set.of());
        lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());

        service = new StudyRoomServiceImpl(groupService, chatRepository, studyRoomChatRepository,
                presenceService, redis, objectMapper, messagingTemplate);

        owner = new User();
        owner.setId(1L);
        owner.setUuid(UUID.randomUUID());
        owner.setUsername("alice");
    }

    private Chat studyRoom(UUID uuid, Long ownerId) {
        Chat c = new Chat();
        c.setUuid(uuid);
        c.setName("Study together");
        c.setDescription("desc");
        c.setOwnerId(ownerId);
        c.setRoomMode(RoomMode.STUDY_POMODORO);
        c.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return c;
    }

    /** Serialize a session the way production stores it, for stubbing valueOps.get(). */
    private String json(StudySessionResponse s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StudySessionResponse idleSession(String roomUuid, int focus, int brk, String subject) {
        return StudySessionResponse.builder()
                .roomUuid(roomUuid).subject(subject).phase(PomodoroPhase.IDLE.name())
                .phaseEndsAtEpochMs(0).focusMinutes(focus).breakMinutes(brk)
                .ownerUsername("alice").active(false)
                .build();
    }

    // ── createStudyRoom ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createStudyRoom")
    class Create {

        @Test
        @DisplayName("custom name/subject/minutes → flips to STUDY_POMODORO, saves, seeds IDLE session")
        void createsCustom() throws Exception {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(owner)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            Chat chat = studyRoom(uuid, 1L);
            chat.setRoomMode(RoomMode.STANDARD);
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));

            StudyRoomResponse res = service.createStudyRoom(owner, "  Calc Grind  ", "  Calculus  ", 50, 10);

            assertThat(chat.getRoomMode()).isEqualTo(RoomMode.STUDY_POMODORO);
            verify(chatRepository).save(chat);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(owner));
            assertThat(req.getValue().getName()).isEqualTo("Calc Grind");
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getVisibility()).isEqualTo("PUBLIC");
            assertThat(req.getValue().getCategory()).isEqualTo("study");

            // Seeded IDLE session persisted with the trimmed subject + minutes.
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(SESSION_PREFIX + uuid), body.capture(), any());
            StudySessionResponse seeded = objectMapper.readValue(body.getValue(), StudySessionResponse.class);
            assertThat(seeded.getPhase()).isEqualTo("IDLE");
            assertThat(seeded.getSubject()).isEqualTo("Calculus");
            assertThat(seeded.getFocusMinutes()).isEqualTo(50);
            assertThat(seeded.getBreakMinutes()).isEqualTo(10);

            assertThat(res.getSubject()).isEqualTo("Calculus");
            assertThat(res.getPhase()).isEqualTo("IDLE");
            assertThat(res.getRoomMode()).isEqualTo("STUDY_POMODORO");
        }

        @Test
        @DisplayName("null name/minutes → default name + 25/5 defaults")
        void createsDefaults() throws Exception {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(owner)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));

            service.createStudyRoom(owner, null, null, null, null);

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(owner));
            assertThat(req.getValue().getName()).isEqualTo("Study together");

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(anyString(), body.capture(), any());
            StudySessionResponse seeded = objectMapper.readValue(body.getValue(), StudySessionResponse.class);
            assertThat(seeded.getFocusMinutes()).isEqualTo(25);
            assertThat(seeded.getBreakMinutes()).isEqualTo(5);
            assertThat(seeded.getSubject()).isNull();
        }

        @Test
        @DisplayName("out-of-range minutes are clamped (focus→180, break→1)")
        void clampsMinutes() throws Exception {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(owner)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));

            service.createStudyRoom(owner, "x", null, 9999, 0);

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(anyString(), body.capture(), any());
            StudySessionResponse seeded = objectMapper.readValue(body.getValue(), StudySessionResponse.class);
            assertThat(seeded.getFocusMinutes()).isEqualTo(180);
            assertThat(seeded.getBreakMinutes()).isEqualTo(1);
        }

        @Test
        @DisplayName("created room cannot be re-loaded → NotFoundException TM_905, no save")
        void roomNotFound() {
            UUID uuid = UUID.randomUUID();
            when(groupService.createGroup(any(), eq(owner)))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createStudyRoom(owner, "x", null, null, null))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_905"));
            verify(chatRepository, never()).save(any());
        }
    }

    // ── requireStudyRoom guards (via getSession-less paths) ─────────────────────────

    @Nested
    @DisplayName("room guards")
    class Guards {

        @Test
        @DisplayName("malformed uuid → BadRequestException TM_900")
        void badUuid() {
            assertThatThrownBy(() -> service.enter(owner, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_900"));
        }

        @Test
        @DisplayName("unknown room → NotFoundException TM_901")
        void unknownRoom() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.enter(owner, uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_901"));
        }

        @Test
        @DisplayName("wrong room mode → NotFoundException TM_901")
        void wrongMode() {
            UUID uuid = UUID.randomUUID();
            Chat chat = studyRoom(uuid, 1L);
            chat.setRoomMode(RoomMode.STANDARD);
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(chat));
            assertThatThrownBy(() -> service.enter(owner, uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_901"));
        }
    }

    // ── startTimer ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("startTimer")
    class Start {

        @Test
        @DisplayName("non-owner → ForbiddenException TM_903, no state written")
        void nonOwner() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 999L)));

            assertThatThrownBy(() -> service.startTimer(owner, uuid.toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_903"));
            verify(valueOps, never()).set(anyString(), anyString(), any());
            verify(zSetOps, never()).add(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        }

        @Test
        @DisplayName("owner → FOCUS armed with the room's focus minutes + pomodoro_started broadcast")
        void ownerStarts() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(json(idleSession(uuid.toString(), 50, 10, "Calc")));

            StudySessionResponse res = service.startTimer(owner, uuid.toString());

            assertThat(res.getPhase()).isEqualTo("FOCUS");
            assertThat(res.isActive()).isTrue();
            assertThat(res.getSubject()).isEqualTo("Calc");
            // ~50 minutes remaining.
            assertThat(res.getRemainingSeconds()).isBetween(50L * 60 - 5, 50L * 60);

            // Deadline armed in the ZSET for this room.
            ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
            verify(zSetOps).add(eq(DEADLINE_ZSET), eq(uuid.toString()), score.capture());
            assertThat(score.getValue()).isGreaterThan(System.currentTimeMillis());

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }

        @Test
        @DisplayName("owner with no seeded session → falls back to 25/5 defaults")
        void ownerStartsNoSession() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            StudySessionResponse res = service.startTimer(owner, uuid.toString());

            assertThat(res.getFocusMinutes()).isEqualTo(25);
            assertThat(res.getBreakMinutes()).isEqualTo(5);
            assertThat(res.getPhase()).isEqualTo("FOCUS");
        }
    }

    // ── getSession ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSession")
    class GetSession {

        @Test
        @DisplayName("no session → IDLE shell with 0 remaining")
        void idleShell() {
            String uuid = UUID.randomUUID().toString();
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            StudySessionResponse res = service.getSession(uuid);

            assertThat(res.getPhase()).isEqualTo("IDLE");
            assertThat(res.getRemainingSeconds()).isZero();
            assertThat(res.isActive()).isFalse();
        }

        @Test
        @DisplayName("live FOCUS session → positive remaining seconds computed from the deadline")
        void liveSession() {
            String uuid = UUID.randomUUID().toString();
            StudySessionResponse live = StudySessionResponse.builder()
                    .roomUuid(uuid).phase(PomodoroPhase.FOCUS.name())
                    .phaseEndsAtEpochMs(System.currentTimeMillis() + 120_000)
                    .focusMinutes(25).breakMinutes(5).active(true).build();
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(json(live));

            StudySessionResponse res = service.getSession(uuid);

            assertThat(res.getPhase()).isEqualTo("FOCUS");
            assertThat(res.getRemainingSeconds()).isBetween(110L, 120L);
            assertThat(res.isActive()).isTrue();
        }
    }

    // ── setGoal ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setGoal")
    class SetGoal {

        @Test
        @DisplayName("blank text → BadRequestException TM_904")
        void blankGoal() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));

            assertThatThrownBy(() -> service.setGoal(owner, uuid.toString(), "  "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_904"));
        }

        @Test
        @DisplayName("valid goal on existing session → stored under username + goal_set broadcast")
        void storesGoal() throws Exception {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(json(idleSession(uuid.toString(), 25, 5, "Calc")));

            StudySessionResponse res = service.setGoal(owner, uuid.toString(), "Finish chapter 3");

            assertThat(res.getGoals()).containsEntry("alice", "Finish chapter 3");
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(SESSION_PREFIX + uuid), body.capture(), any());
            assertThat(objectMapper.readValue(body.getValue(), StudySessionResponse.class)
                    .getGoals()).containsEntry("alice", "Finish chapter 3");
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }

        @Test
        @DisplayName("valid goal with no session yet → seeds a session and stores the goal")
        void seedsThenStores() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            StudySessionResponse res = service.setGoal(owner, uuid.toString(), "Read notes");

            assertThat(res.getGoals()).containsEntry("alice", "Read notes");
            verify(valueOps).set(eq(SESSION_PREFIX + uuid), anyString(), any());
        }
    }

    // ── imStuck ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("imStuck")
    class Stuck {

        @Test
        @DisplayName("broadcasts stuck event to the room; null note tolerated")
        void broadcastsStuck() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            service.imStuck(owner, uuid.toString(), null);

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }
    }

    // ── roster + join ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("roster + join")
    class Roster {

        @Test
        @DisplayName("enter → adds username to the presence set + user_joined broadcast")
        void enter() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            service.enter(owner, uuid.toString());

            verify(setOps).add(PRESENCE_PREFIX + uuid, "alice");
            verify(redis).expire(eq(PRESENCE_PREFIX + uuid), any());
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }

        @Test
        @DisplayName("leave → removes username from the presence set + user_left broadcast")
        void leave() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));

            service.leave(owner, uuid.toString());

            verify(setOps).remove(PRESENCE_PREFIX + uuid, "alice");
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }

        @Test
        @DisplayName("joinStudyRoom → delegates to GroupService.joinChat")
        void join() {
            UUID uuid = UUID.randomUUID();
            when(chatRepository.findByUuid(uuid)).thenReturn(Optional.of(studyRoom(uuid, 1L)));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);
            when(groupService.joinChat(uuid.toString(), owner))
                    .thenReturn(ChatResponse.builder().id(uuid.toString()).build());

            StudyRoomResponse res = service.joinStudyRoom(owner, uuid.toString());

            verify(groupService).joinChat(uuid.toString(), owner);
            assertThat(res.getId()).isEqualTo(uuid.toString());
        }
    }

    // ── reapDuePhases ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reapDuePhases")
    class Reap {

        @Test
        @DisplayName("no due deadlines → no writes, no broadcasts")
        void nothingDue() {
            when(zSetOps.rangeByScore(eq(DEADLINE_ZSET), eq(0d), org.mockito.ArgumentMatchers.anyDouble()))
                    .thenReturn(Set.of());

            service.reapDuePhases();

            verify(valueOps, never()).set(anyString(), anyString(), any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("due FOCUS → flips to BREAK, re-arms deadline, broadcasts phase_changed")
        void flipsFocusToBreak() throws Exception {
            String uuid = UUID.randomUUID().toString();
            when(zSetOps.rangeByScore(eq(DEADLINE_ZSET), eq(0d), org.mockito.ArgumentMatchers.anyDouble()))
                    .thenReturn(Set.of(uuid));
            StudySessionResponse focus = StudySessionResponse.builder()
                    .roomUuid(uuid).phase(PomodoroPhase.FOCUS.name())
                    .phaseEndsAtEpochMs(System.currentTimeMillis() - 1000)
                    .focusMinutes(25).breakMinutes(5).active(true).build();
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(json(focus));

            service.reapDuePhases();

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(SESSION_PREFIX + uuid), body.capture(), any());
            StudySessionResponse next = objectMapper.readValue(body.getValue(), StudySessionResponse.class);
            assertThat(next.getPhase()).isEqualTo("BREAK");
            assertThat(next.getPhaseEndsAtEpochMs()).isGreaterThan(System.currentTimeMillis());
            verify(zSetOps).add(eq(DEADLINE_ZSET), eq(uuid), org.mockito.ArgumentMatchers.anyDouble());
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + uuid + "/study"), any(Object.class));
        }

        @Test
        @DisplayName("due BREAK → flips back to FOCUS")
        void flipsBreakToFocus() throws Exception {
            String uuid = UUID.randomUUID().toString();
            when(zSetOps.rangeByScore(eq(DEADLINE_ZSET), eq(0d), org.mockito.ArgumentMatchers.anyDouble()))
                    .thenReturn(Set.of(uuid));
            StudySessionResponse brk = StudySessionResponse.builder()
                    .roomUuid(uuid).phase(PomodoroPhase.BREAK.name())
                    .phaseEndsAtEpochMs(System.currentTimeMillis() - 1000)
                    .focusMinutes(25).breakMinutes(5).active(true).build();
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(json(brk));

            service.reapDuePhases();

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(SESSION_PREFIX + uuid), body.capture(), any());
            assertThat(objectMapper.readValue(body.getValue(), StudySessionResponse.class).getPhase())
                    .isEqualTo("FOCUS");
        }

        @Test
        @DisplayName("due room whose session vanished → dropped from the ZSET, no broadcast")
        void missingSessionDropped() {
            String uuid = UUID.randomUUID().toString();
            when(zSetOps.rangeByScore(eq(DEADLINE_ZSET), eq(0d), org.mockito.ArgumentMatchers.anyDouble()))
                    .thenReturn(Set.of(uuid));
            when(valueOps.get(SESSION_PREFIX + uuid)).thenReturn(null);

            service.reapDuePhases();

            verify(zSetOps).remove(DEADLINE_ZSET, uuid);
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }
}
