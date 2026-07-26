package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ListenerShift;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ListenerShiftResponse;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.RoomMode;
import com.chat.talkMe.enums.ShiftStatus;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.ListenerShiftRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.ListenerService;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Volunteer listener queue for "Someone Is Listening" (features #26/#27).
 *
 * <p>The DB owns fair-queue ordering (oldest AVAILABLE first). The {@code listeners:available}
 * Redis set is a fast, fully fail-open availability mirror — every Redis touch is wrapped and
 * only ever logged at debug, so Redis being down never blocks a listener from clocking on/off
 * or a requester from being matched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListenerServiceImpl implements ListenerService {

    private static final String AVAILABLE_SET = "listeners:available";
    /** People helped within a shift before the shift trends its owner toward Great Listener. */
    private static final int GREAT_LISTENER_THRESHOLD = 3;

    private final ListenerShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final GroupService groupService;
    private final ReputationRecorder reputationRecorder;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional
    public ListenerShiftResponse goAvailable(User user) {
        if (user.isGuest()) {
            throw new ForbiddenException("Guests can't sign up as listeners", "TM_997");
        }
        User listener = userRepository.findById(user.getId()).orElse(user);

        ListenerShift shift = shiftRepository
                .findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED)
                .orElse(null);
        if (shift == null) {
            shift = ListenerShift.builder()
                    .listener(listener)
                    .status(ShiftStatus.AVAILABLE)
                    .startedAt(Instant.now())
                    .peopleHelped(0)
                    .build();
        } else {
            // Re-arm an existing live shift (e.g. was ENGAGED and the session ended).
            shift.setStatus(ShiftStatus.AVAILABLE);
            shift.setRoomChatUuid(null);
        }
        shift = shiftRepository.save(shift);

        addToAvailableSet(listener.getUsername());
        return toResponse(shift);
    }

    @Override
    @Transactional
    public void endShift(User user) {
        ListenerShift shift = shiftRepository
                .findFirstByListenerAndStatusNotOrderByStartedAtDesc(user, ShiftStatus.ENDED)
                .orElse(null);
        // Idempotent: no live shift ⇒ nothing to end (still scrub the availability mirror).
        removeFromAvailableSet(user.getUsername());
        if (shift == null) return;

        // Ending a shift while mid-session counts the person they were helping.
        if (shift.getStatus() == ShiftStatus.ENGAGED) {
            creditHelp(shift);
        }
        shift.setStatus(ShiftStatus.ENDED);
        shift.setEndedAt(Instant.now());
        shiftRepository.save(shift);
    }

    @Override
    @Transactional
    public ListenerShiftResponse requestListener(User requester, com.chat.talkMe.enums.ListenerReason reason) {
        User seeker = userRepository.findById(requester.getId()).orElse(requester);
        com.chat.talkMe.enums.ListenerReason ctx =
                reason == null ? com.chat.talkMe.enums.ListenerReason.NEED_TO_TALK : reason;

        ListenerShift shift = shiftRepository
                .findFirstByStatusAndListenerNotOrderByStartedAtAsc(ShiftStatus.AVAILABLE, seeker)
                .orElseThrow(() -> new NotFoundException("No listener is available right now", "TM_993"));

        User listener = shift.getListener();

        // Spin up an ephemeral LISTENING-mode room, hosted by the volunteer listener. createGroup
        // forces a ROOM to PUBLIC/OPEN, so we create it, admit ONLY the seeker while it is briefly
        // open, then lock it down to PRIVATE/INVITE_ONLY — all inside this one transaction, so no
        // other user ever sees it in its open state. This keeps a vulnerable person's support
        // session strictly 1:1 and undiscoverable (a PUBLIC/OPEN support room would let any
        // authenticated user find it via /groups/discover and join to eavesdrop).
        CreateGroupRequest req = new CreateGroupRequest();
        req.setName("Someone is listening · " + ctx.getLabel());
        req.setDescription("A private space to talk. Nothing said here is recorded.");
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory("support");
        ChatResponse room = groupService.createGroup(req, listener);

        // Admit the seeker while the room is still open-join, then lock the room down. Both happen
        // in this transaction — the committed room is PRIVATE with exactly the two participants.
        groupService.joinChat(room.getId(), seeker);

        Chat chat = chatRepository.findByUuid(UUID.fromString(room.getId()))
                .orElseThrow(() -> new NotFoundException("Room not found", "TM_998"));
        chat.setRoomMode(RoomMode.LISTENING);
        chat.setVisibility(com.chat.talkMe.enums.ChatVisibility.PRIVATE);
        chat.setJoinPolicy(com.chat.talkMe.enums.JoinPolicy.INVITE_ONLY);
        chatRepository.save(chat);

        // Bind the shift to the room and mark it engaged.
        shift.setStatus(ShiftStatus.ENGAGED);
        shift.setRoomChatUuid(room.getId());
        shift = shiftRepository.save(shift);
        removeFromAvailableSet(listener.getUsername());

        // Nudge the listener that they've been matched.
        broadcastToListener(listener.getUsername(), "listener_engaged", Map.of(
                "roomChatUuid", room.getId(),
                "shiftId", shift.getUuid().toString()));

        return toResponse(shift);
    }

    @Override
    @Transactional
    public ListenerShiftResponse completeShift(User listener) {
        ListenerShift shift = shiftRepository
                .findFirstByListenerAndStatusNotOrderByStartedAtDesc(listener, ShiftStatus.ENDED)
                .orElseThrow(() -> new NotFoundException("You are not on an active listening shift", "TM_996"));

        // Only credit a genuinely completed session: the shift must be ENGAGED with a bound room.
        // Otherwise a listener could farm the Great Listener trend by spamming /complete while idle.
        if (shift.getStatus() == ShiftStatus.ENGAGED && shift.getRoomChatUuid() != null) {
            creditHelp(shift);
        }
        // Back on duty for the next person.
        shift.setStatus(ShiftStatus.AVAILABLE);
        shift.setRoomChatUuid(null);
        shift = shiftRepository.save(shift);
        addToAvailableSet(shift.getListener().getUsername());

        return toResponse(shift);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListenerShiftResponse> listAvailable() {
        List<ListenerShiftResponse> out = new ArrayList<>();
        for (ListenerShift shift : shiftRepository.findByStatusOrderByStartedAtAsc(ShiftStatus.AVAILABLE)) {
            out.add(toResponse(shift));
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Credit one helped person and, at the threshold, trend the listener toward Great Listener. */
    private void creditHelp(ListenerShift shift) {
        shift.setPeopleHelped(shift.getPeopleHelped() + 1);
        if (shift.getPeopleHelped() >= GREAT_LISTENER_THRESHOLD) {
            // Cosmetic, fire-and-forget: no direct BadgeService award API exists (endorsements are
            // peer-driven), so we feed the reputation ledger. See wiringSpec for the follow-up hook.
            try {
                reputationRecorder.record(
                        shift.getListener().getId(),
                        ReputationEventType.EVENT_ATTENDED,
                        "listener_great:" + shift.getUuid());
            } catch (Exception e) {
                log.debug("reputation record for great-listener trend failed: {}", e.getMessage());
            }
        }
    }

    private ListenerShiftResponse toResponse(ListenerShift shift) {
        User l = shift.getListener();
        return ListenerShiftResponse.builder()
                .id(shift.getUuid().toString())
                .listenerId(l.getUuid().toString())
                .listenerName(l.getName())
                .listenerUsername(l.getUsername())
                .listenerAvatar(l.getProfileImage())
                .status(shift.getStatus().name())
                .roomChatUuid(shift.getRoomChatUuid())
                .peopleHelped(shift.getPeopleHelped())
                .startedAt(shift.getStartedAt())
                .endedAt(shift.getEndedAt())
                .build();
    }

    private void addToAvailableSet(String username) {
        try {
            redis.opsForSet().add(AVAILABLE_SET, username);
        } catch (Exception e) {
            log.debug("listeners:available add failed (fail-open): {}", e.getMessage());
        }
    }

    private void removeFromAvailableSet(String username) {
        try {
            redis.opsForSet().remove(AVAILABLE_SET, username);
        } catch (Exception e) {
            log.debug("listeners:available remove failed (fail-open): {}", e.getMessage());
        }
    }

    private void broadcastToListener(String username, String event, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend("/topic/listener/" + username,
                    (Object) Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("listener WS broadcast failed: {}", e.getMessage());
        }
    }
}
