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
import com.neo.chat.service.StudyRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Focus/study rooms driven by a shared Pomodoro timer (STUDY_ROOMS).
 *
 * <p>A study room is an ordinary public ROOM flipped into {@link RoomMode#STUDY_POMODORO} (ephemeral
 * — messages are not recorded, enforced server-side in the send path). We reuse
 * {@link GroupService#createGroup} to build the room, then set the mode on the loaded Chat — never
 * editing the shared Chat/ChatRepository definitions.
 *
 * <p>All Pomodoro state is ephemeral and Redis-backed:
 * <ul>
 *   <li>the shared session (phase, deadline, focus/break minutes, subject, per-user goals) lives in
 *       the Redis string {@code study:session:{roomUuid}} (JSON, {@value SESSION_TTL_HOURS}h TTL),
 *       mirroring the MusicSession pattern;</li>
 *   <li>the per-room phase deadline is mirrored into the Redis ZSET {@value #DEADLINE_ZSET} so a
 *       central {@code StudyPomodoroReaper} can flip FOCUS ⇄ BREAK server-authoritatively,
 *       mirroring the MatchTimer pattern;</li>
 *   <li>the live "who's here now" roster is a Redis set {@code study:presence:{roomUuid}}, self-healed
 *       against the global online set, mirroring the City pattern.</li>
 * </ul>
 * Every Redis touch fails open (logs at debug, never blocks the request) and every mutation
 * broadcasts on {@code /topic/chat/{roomUuid}/study}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyRoomServiceImpl implements StudyRoomService {

    private static final String DEFAULT_NAME = "Study together";
    private static final String CATEGORY = "study";

    private static final String SESSION_PREFIX = "study:session:";
    private static final String PRESENCE_PREFIX = "study:presence:";
    private static final String DEADLINE_ZSET = "study:pomodoro-deadlines";

    private static final long SESSION_TTL_HOURS = 12;
    private static final Duration SESSION_TTL = Duration.ofHours(SESSION_TTL_HOURS);
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    private static final int DEFAULT_FOCUS_MIN = 25;
    private static final int DEFAULT_BREAK_MIN = 5;
    private static final int MAX_FOCUS_MIN = 180;
    private static final int MAX_BREAK_MIN = 60;
    private static final int MAX_GOAL_LEN = 280;
    private static final int MAX_NOTE_LEN = 280;

    private final GroupService groupService;
    private final ChatRepository chatRepository;
    private final StudyRoomChatRepository studyRoomChatRepository;
    private final PresenceService presenceService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Room lifecycle ──────────────────────────────────────────────────────────

    /**
     * Creates a public ROOM via {@link GroupService#createGroup} and flips it into
     * {@link RoomMode#STUDY_POMODORO}, then seeds an IDLE session in Redis (carrying the subject and
     * focus/break minutes so a later {@link #startTimer} needs no separate config lookup).
     *
     * @param user         the owner/creator
     * @param name         optional room name; blank/null ⇒ {@value #DEFAULT_NAME}
     * @param subject      optional free-form subject/topic
     * @param focusMinutes optional focus length; null ⇒ {@value #DEFAULT_FOCUS_MIN}, clamped 1..{@value #MAX_FOCUS_MIN}
     * @param breakMinutes optional break length; null ⇒ {@value #DEFAULT_BREAK_MIN}, clamped 1..{@value #MAX_BREAK_MIN}
     * @return the created study room card
     * @throws NotFoundException if the just-created room cannot be reloaded (TM_905)
     */
    @Override
    @Transactional
    public StudyRoomResponse createStudyRoom(User user, String name, String subject,
                                             Integer focusMinutes, Integer breakMinutes) {
        String roomName = (name != null && !name.isBlank()) ? name.trim() : DEFAULT_NAME;
        String subjectTrimmed = (subject != null && !subject.isBlank()) ? subject.trim() : null;
        int focus = clamp(focusMinutes, DEFAULT_FOCUS_MIN, 1, MAX_FOCUS_MIN);
        int brk = clamp(breakMinutes, DEFAULT_BREAK_MIN, 1, MAX_BREAK_MIN);

        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(roomName);
        req.setDescription(subjectTrimmed != null
                ? "Focus together with a shared Pomodoro timer. Topic: " + subjectTrimmed
                : "Focus together with a shared Pomodoro timer.");
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory(CATEGORY);
        ChatResponse room = groupService.createGroup(req, user);

        Chat chat = chatRepository.findByUuid(UUID.fromString(room.getId()))
                .orElseThrow(() -> new NotFoundException("Room not found", "TM_905"));
        chat.setRoomMode(RoomMode.STUDY_POMODORO);
        chatRepository.save(chat);

        String roomUuid = chat.getUuid().toString();
        // Seed an IDLE session carrying config so listing/start can read it without a DB round-trip.
        StudySessionResponse idle = StudySessionResponse.builder()
                .roomUuid(roomUuid)
                .subject(subjectTrimmed)
                .phase(PomodoroPhase.IDLE.name())
                .phaseEndsAtEpochMs(0)
                .focusMinutes(focus)
                .breakMinutes(brk)
                .ownerUsername(user.getUsername())
                .goals(new LinkedHashMap<>())
                .active(false)
                .build();
        writeState(roomUuid, idle);

        return toCard(chat, idle);
    }

    /**
     * Lists all active STUDY_POMODORO rooms with a live phase/remaining snapshot and online
     * participant count. Read-only transaction; Redis reads fail open.
     */
    @Override
    @Transactional(readOnly = true)
    public List<StudyRoomResponse> listStudyRooms() {
        Set<String> online = safeOnline();
        List<StudyRoomResponse> out = new ArrayList<>();
        for (Chat chat : studyRoomChatRepository.findActiveByRoomMode(RoomMode.STUDY_POMODORO)) {
            StudySessionResponse session = readFreshState(chat.getUuid().toString());
            out.add(toCard(chat, session, liveCount(chat.getUuid().toString(), online)));
        }
        return out;
    }

    /**
     * Joins the study room's underlying chat via the shared open-join flow, then returns the card.
     *
     * @throws BadRequestException malformed room uuid (TM_900)
     * @throws NotFoundException   unknown room / not a study room (TM_901)
     */
    @Override
    @Transactional
    public StudyRoomResponse joinStudyRoom(User user, String roomUuid) {
        Chat chat = requireStudyRoom(roomUuid);
        groupService.joinChat(roomUuid, user);
        return toCard(chat, readFreshState(roomUuid));
    }

    // ── Live roster ──────────────────────────────────────────────────────────────

    /**
     * Marks the user present in the room's Redis roster (best-effort, refreshes TTL), broadcasts a
     * {@code user_joined} event, and returns the refreshed card.
     */
    @Override
    @Transactional(readOnly = true)
    public StudyRoomResponse enter(User user, String roomUuid) {
        Chat chat = requireStudyRoom(roomUuid);
        String key = presenceKey(roomUuid);
        try {
            redis.opsForSet().add(key, user.getUsername());
            redis.expire(key, PRESENCE_TTL);
        } catch (Exception e) {
            log.debug("Study enter presence write skipped for {} / {}: {}", user.getUsername(), key, e.getMessage());
        }
        broadcast(roomUuid, "user_joined", Map.of("username", user.getUsername(),
                "liveCount", liveCount(roomUuid, safeOnline())));
        return toCard(chat, readFreshState(roomUuid));
    }

    /**
     * Removes the user from the room's Redis roster (best-effort) and broadcasts {@code user_left}.
     */
    @Override
    @Transactional(readOnly = true)
    public void leave(User user, String roomUuid) {
        requireStudyRoom(roomUuid);
        try {
            redis.opsForSet().remove(presenceKey(roomUuid), user.getUsername());
        } catch (Exception e) {
            log.debug("Study leave presence write skipped for {}: {}", user.getUsername(), e.getMessage());
        }
        broadcast(roomUuid, "user_left", Map.of("username", user.getUsername(),
                "liveCount", liveCount(roomUuid, safeOnline())));
    }

    // ── Pomodoro session ────────────────────────────────────────────────────────

    /**
     * Owner-only: starts the Pomodoro. Records the FOCUS deadline in the session JSON + the deadline
     * ZSET and broadcasts {@code pomodoro_started}. Falls back to the default focus/break minutes if
     * the seeded config was lost.
     *
     * @throws BadRequestException malformed room uuid (TM_900)
     * @throws NotFoundException   unknown room / not a study room (TM_901)
     * @throws ForbiddenException  the caller is not the room owner (TM_903)
     */
    @Override
    @Transactional(readOnly = true)
    public StudySessionResponse startTimer(User user, String roomUuid) {
        Chat chat = requireStudyRoom(roomUuid);
        if (chat.getOwnerId() == null || user.getId() == null || !chat.getOwnerId().equals(user.getId())) {
            throw new ForbiddenException("Only the room owner can start the timer", "TM_903");
        }
        StudySessionResponse existing = readState(roomUuid);
        int focus = existing != null && existing.getFocusMinutes() > 0 ? existing.getFocusMinutes() : DEFAULT_FOCUS_MIN;
        int brk = existing != null && existing.getBreakMinutes() > 0 ? existing.getBreakMinutes() : DEFAULT_BREAK_MIN;
        String subject = existing != null ? existing.getSubject() : null;
        Map<String, String> goals = existing != null && existing.getGoals() != null
                ? existing.getGoals() : new LinkedHashMap<>();

        long now = System.currentTimeMillis();
        long deadline = now + focus * 60_000L;
        StudySessionResponse state = StudySessionResponse.builder()
                .roomUuid(roomUuid)
                .subject(subject)
                .phase(PomodoroPhase.FOCUS.name())
                .phaseEndsAtEpochMs(deadline)
                .focusMinutes(focus)
                .breakMinutes(brk)
                .ownerUsername(user.getUsername())
                .goals(goals)
                .active(true)
                .build();
        writeState(roomUuid, state);
        armDeadline(roomUuid, deadline);
        stamp(state, now);
        broadcast(roomUuid, "pomodoro_started", state);
        log.info("Pomodoro started for study room {} by {} (focus={}m, break={}m)",
                roomUuid, user.getUuid(), focus, brk);
        return state;
    }

    /**
     * Returns the current session with a fresh phase/remaining computed from the stored deadline, or
     * an IDLE shell when none is live. Reads Redis (fails open). No membership guard — this is a
     * discovery read (the STUDY_ROOMS feature gate on the route is the entitlement check).
     */
    @Override
    @Transactional(readOnly = true)
    public StudySessionResponse getSession(String roomUuid) {
        StudySessionResponse state = readFreshState(roomUuid);
        if (state != null) {
            return state;
        }
        long now = System.currentTimeMillis();
        return StudySessionResponse.builder()
                .roomUuid(roomUuid)
                .phase(PomodoroPhase.IDLE.name())
                .phaseEndsAtEpochMs(0)
                .remainingSeconds(0)
                .focusMinutes(DEFAULT_FOCUS_MIN)
                .breakMinutes(DEFAULT_BREAK_MIN)
                .goals(new LinkedHashMap<>())
                .active(false)
                .serverTimeEpochMs(now)
                .build();
    }

    /**
     * The "I'm stuck" button: broadcasts a {@code stuck} event (who + optional trimmed note) to the
     * room so others can help. Returns the current session snapshot.
     *
     * @throws BadRequestException malformed room uuid (TM_900)
     * @throws NotFoundException   unknown room / not a study room (TM_901)
     */
    @Override
    @Transactional(readOnly = true)
    public StudySessionResponse imStuck(User user, String roomUuid, String note) {
        requireStudyRoom(roomUuid);
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > MAX_NOTE_LEN) {
            trimmed = trimmed.substring(0, MAX_NOTE_LEN);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", user.getUsername());
        payload.put("note", trimmed);
        broadcast(roomUuid, "stuck", payload);
        return getSession(roomUuid);
    }

    /**
     * Sets the caller's shared session goal in the per-username goal map on the session JSON and
     * broadcasts {@code goal_set}. Seeds an IDLE session (with default config) if none exists yet so
     * goals can be set before the owner starts the timer.
     *
     * @throws BadRequestException malformed room uuid (TM_900) or blank goal text (TM_904)
     * @throws NotFoundException   unknown room / not a study room (TM_901)
     */
    @Override
    @Transactional(readOnly = true)
    public StudySessionResponse setGoal(User user, String roomUuid, String text) {
        requireStudyRoom(roomUuid);
        if (text == null || text.isBlank()) {
            throw new BadRequestException("Goal text is required", "TM_904");
        }
        String goal = text.trim();
        if (goal.length() > MAX_GOAL_LEN) {
            goal = goal.substring(0, MAX_GOAL_LEN);
        }
        StudySessionResponse state = readState(roomUuid);
        if (state == null) {
            state = StudySessionResponse.builder()
                    .roomUuid(roomUuid)
                    .phase(PomodoroPhase.IDLE.name())
                    .phaseEndsAtEpochMs(0)
                    .focusMinutes(DEFAULT_FOCUS_MIN)
                    .breakMinutes(DEFAULT_BREAK_MIN)
                    .goals(new LinkedHashMap<>())
                    .active(false)
                    .build();
        }
        if (state.getGoals() == null) {
            state.setGoals(new LinkedHashMap<>());
        }
        state.getGoals().put(user.getUsername(), goal);
        writeState(roomUuid, state);
        refresh(state);
        broadcast(roomUuid, "goal_set", Map.of("username", user.getUsername(), "goal", goal, "session", state));
        return state;
    }

    // ── Reaper ────────────────────────────────────────────────────────────────────

    /**
     * Reaper pass: for each room whose current phase deadline has elapsed, flips FOCUS ⇄ BREAK,
     * extends the deadline (using the room's own focus/break minutes), persists, re-arms the ZSET,
     * and broadcasts {@code phase_changed}. A room whose session JSON has vanished is dropped from
     * the ZSET. Fails open per entry so one bad row never stalls the batch.
     */
    @Override
    public void reapDuePhases() {
        long now = System.currentTimeMillis();
        Set<String> due = redis.opsForZSet().rangeByScore(DEADLINE_ZSET, 0, now);
        if (due == null || due.isEmpty()) {
            return;
        }
        for (String roomUuid : due) {
            try {
                StudySessionResponse state = readState(roomUuid);
                if (state == null || PomodoroPhase.IDLE.name().equals(state.getPhase())) {
                    redis.opsForZSet().remove(DEADLINE_ZSET, roomUuid);
                    continue;
                }
                boolean wasFocus = PomodoroPhase.FOCUS.name().equals(state.getPhase());
                PomodoroPhase next = wasFocus ? PomodoroPhase.BREAK : PomodoroPhase.FOCUS;
                int minutes = next == PomodoroPhase.FOCUS
                        ? Math.max(1, state.getFocusMinutes())
                        : Math.max(1, state.getBreakMinutes());
                long deadline = now + minutes * 60_000L;
                state.setPhase(next.name());
                state.setPhaseEndsAtEpochMs(deadline);
                state.setActive(true);
                writeState(roomUuid, state);
                armDeadline(roomUuid, deadline);
                stamp(state, now);
                broadcast(roomUuid, "phase_changed", state);
            } catch (Exception e) {
                // Per-entry fail-open: drop the offender so the batch keeps flowing.
                log.debug("Study phase reap skipped for {}: {}", roomUuid, e.getMessage());
                try {
                    redis.opsForZSet().remove(DEADLINE_ZSET, roomUuid);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    /**
     * Resolves a room uuid to its Chat and asserts it is a STUDY_POMODORO room.
     *
     * @throws BadRequestException malformed uuid (TM_900)
     * @throws NotFoundException   unknown room or wrong mode (TM_901)
     */
    private Chat requireStudyRoom(String roomUuid) {
        Chat chat;
        try {
            chat = chatRepository.findByUuid(UUID.fromString(roomUuid)).orElse(null);
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid room id", "TM_900");
        }
        if (chat == null || chat.isDeleted() || chat.getRoomMode() != RoomMode.STUDY_POMODORO) {
            throw new NotFoundException("Study room not found", "TM_901");
        }
        return chat;
    }

    private static int clamp(Integer value, int dflt, int min, int max) {
        if (value == null) return dflt;
        return Math.max(min, Math.min(max, value));
    }

    private StudyRoomResponse toCard(Chat chat, StudySessionResponse session) {
        return toCard(chat, session, liveCount(chat.getUuid().toString(), safeOnline()));
    }

    private StudyRoomResponse toCard(Chat chat, StudySessionResponse session, int participantCount) {
        return StudyRoomResponse.builder()
                .id(chat.getUuid().toString())
                .name(chat.getName())
                .subject(session != null ? session.getSubject() : null)
                .description(chat.getDescription())
                .roomMode(chat.getRoomMode() != null ? chat.getRoomMode().name() : RoomMode.STANDARD.name())
                .phase(session != null ? session.getPhase() : PomodoroPhase.IDLE.name())
                .remainingSeconds(session != null ? session.getRemainingSeconds() : 0)
                .participantCount(participantCount)
                .createdAt(chat.getCreatedAt())
                .build();
    }

    /**
     * Arms/refreshes the per-room phase deadline in the ZSET (best-effort).
     */
    private void armDeadline(String roomUuid, long deadlineEpochMs) {
        try {
            redis.opsForZSet().add(DEADLINE_ZSET, roomUuid, deadlineEpochMs);
        } catch (Exception e) {
            log.debug("Study deadline arm skipped for {}: {}", roomUuid, e.getMessage());
        }
    }

    /**
     * Reads the session and stamps a fresh server clock + remaining seconds, or null if absent.
     */
    private StudySessionResponse readFreshState(String roomUuid) {
        StudySessionResponse state = readState(roomUuid);
        if (state == null) {
            return null;
        }
        refresh(state);
        return state;
    }

    /**
     * Recomputes {@code serverTimeEpochMs} + {@code remainingSeconds} from the stored deadline.
     */
    private void refresh(StudySessionResponse state) {
        stamp(state, System.currentTimeMillis());
    }

    private void stamp(StudySessionResponse state, long now) {
        state.setServerTimeEpochMs(now);
        boolean running = state.getPhaseEndsAtEpochMs() > 0
                && !PomodoroPhase.IDLE.name().equals(state.getPhase());
        long remaining = running ? Math.max(0, (state.getPhaseEndsAtEpochMs() - now) / 1000L) : 0;
        state.setRemainingSeconds(remaining);
        state.setActive(running);
    }

    private StudySessionResponse readState(String roomUuid) {
        try {
            String cached = redis.opsForValue().get(sessionKey(roomUuid));
            if (cached != null) {
                return objectMapper.readValue(cached, StudySessionResponse.class);
            }
        } catch (Exception e) {
            log.debug("Study session read skipped for {}: {}", roomUuid, e.getMessage());
        }
        return null;
    }

    private void writeState(String roomUuid, StudySessionResponse state) {
        try {
            redis.opsForValue().set(sessionKey(roomUuid), objectMapper.writeValueAsString(state), SESSION_TTL);
        } catch (Exception e) {
            log.debug("Study session write skipped for {}: {}", roomUuid, e.getMessage());
        }
    }

    /**
     * Online participant count: the room's Redis roster intersected with the global online set;
     * self-heals stale offline entries best-effort. Fails open to 0.
     */
    private int liveCount(String roomUuid, Set<String> online) {
        Set<String> members = members(roomUuid);
        if (members.isEmpty()) {
            return 0;
        }
        List<String> live = members.stream().filter(online::contains).sorted().toList();
        if (members.size() > live.size()) {
            try {
                Set<String> stale = new HashSet<>(members);
                live.forEach(stale::remove);
                if (!stale.isEmpty()) {
                    redis.opsForSet().remove(presenceKey(roomUuid), stale.toArray());
                }
            } catch (Exception e) {
                log.debug("Study roster self-heal skipped for {}: {}", roomUuid, e.getMessage());
            }
        }
        return live.size();
    }

    private Set<String> members(String roomUuid) {
        try {
            Set<String> m = redis.opsForSet().members(presenceKey(roomUuid));
            return m != null ? m : Collections.emptySet();
        } catch (Exception e) {
            log.debug("Study presence read skipped for {}: {}", roomUuid, e.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> safeOnline() {
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            return online != null ? online : Collections.emptySet();
        } catch (Exception e) {
            log.debug("Study online-presence lookup failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Publishes an {@code {event, payload}} envelope to {@code /topic/chat/{roomUuid}/study}; fails
     * open on any error.
     */
    private void broadcast(String roomUuid, String event, Object payload) {
        try {
            messagingTemplate.convertAndSend("/topic/chat/" + roomUuid + "/study",
                    (Object) Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("Study WS broadcast skipped for {} / {}: {}", event, roomUuid, e.getMessage());
        }
    }

    private static String sessionKey(String roomUuid) {
        return SESSION_PREFIX + roomUuid;
    }

    private static String presenceKey(String roomUuid) {
        return PRESENCE_PREFIX + roomUuid;
    }
}
