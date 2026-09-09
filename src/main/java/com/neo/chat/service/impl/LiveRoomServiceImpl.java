package com.neo.chat.service.impl;

import com.neo.chat.cache.MemberCountCache;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateGroupRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.LiveRoomResponse;
import com.neo.chat.dto.response.TranslateResponse;
import com.neo.chat.enums.GameType;
import com.neo.chat.enums.RoomMode;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.TopicRoomChatRepository;
import com.neo.chat.enums.GamePromptBank;
import com.neo.chat.service.GroupService;
import com.neo.chat.service.LiveRoomService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live Rooms (Connect Wave-2): "third place" topic rooms ({@link RoomMode#TOPIC}) and
 * language-practice rooms ({@link RoomMode#LANGUAGE_PRACTICE}).
 *
 * <p>A room is built by delegating to {@link GroupService#createGroup} with {@code subtype="room"}
 * (which forces PUBLIC + OPEN-join + allow-non-friends), then the loaded {@link Chat} is flipped
 * into the relevant {@link RoomMode} and saved — never editing the shared Chat definition. This
 * mirrors {@code SleepRoomServiceImpl} exactly.
 *
 * <p>Live "who's here now" presence copies the {@code CityServiceImpl} pattern: a per-room Redis
 * set of usernames ({@code liveroom:presence:{uuid}}) intersected with the global online set on
 * every read; enter/leave broadcast {@code user_joined}/{@code user_left} to
 * {@code /topic/chat/{uuid}/room}. Everything Redis-backed is fail-open. Language rooms add inline
 * translation ({@link TranslationService}) and conversation starters ({@link GamePromptBank}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveRoomServiceImpl implements LiveRoomService {

    private static final String KEY_PREFIX = "liveroom:presence:";
    /**
     * Safety expiry so an abandoned room set can't linger forever; refreshed on enter.
     */
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    /**
     * GameTypes whose first prompt seeds the conversation-starter suggestions (deterministic order).
     */
    private static final List<GameType> STARTER_TYPES = List.of(
            GameType.THIS_OR_THAT,
            GameType.WOULD_YOU_RATHER,
            GameType.TRUTH,
            GameType.FINISH_THE_SENTENCE,
            GameType.RAPID_FIRE);

    private final GroupService groupService;
    private final ChatRepository chatRepository;
    private final TopicRoomChatRepository topicRoomChatRepository;
    private final TranslationService translationService;
    private final MemberCountCache memberCountCache;
    private final PresenceService presenceService;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LiveRoomResponse createTopicRoom(User user, String name, String category, List<String> tags) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Room name is required", "TM_928");
        }
        String cat = (category != null && !category.isBlank()) ? category.trim() : "topic";

        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(name.trim());
        req.setDescription("A third place to hang out and chat about " + cat + ".");
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory(cat);
        req.setTags(tags);
        ChatResponse room = groupService.createGroup(req, user);

        Chat chat = reload(room.getId());
        chat.setRoomMode(RoomMode.TOPIC);
        chatRepository.save(chat);
        return toResponse(chat, safeOnline());
    }

    @Override
    @Transactional
    public LiveRoomResponse createLanguageRoom(User user, String name, String targetLanguage, String nativeLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()
                || nativeLanguage == null || nativeLanguage.isBlank()) {
            throw new BadRequestException("Both target and native languages are required", "TM_929");
        }
        String target = targetLanguage.trim();
        String nativeLang = nativeLanguage.trim();
        // Encode the language pair into the category since Chat has no language columns.
        String category = "lang:" + nativeLang + ">" + target;
        String roomName = (name != null && !name.isBlank())
                ? name.trim()
                : "Practice " + target + " (" + nativeLang + " → " + target + ")";

        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(roomName);
        req.setDescription("Language exchange: native " + nativeLang + " practicing " + target + ".");
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory(category);
        ChatResponse room = groupService.createGroup(req, user);

        Chat chat = reload(room.getId());
        chat.setRoomMode(RoomMode.LANGUAGE_PRACTICE);
        chatRepository.save(chat);
        return toResponse(chat, safeOnline());
    }

    // ── Lists ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LiveRoomResponse> listTopicRooms() {
        return mapRooms(topicRoomChatRepository.findActiveByRoomModeIn(List.of(RoomMode.TOPIC)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LiveRoomResponse> listLanguageRooms() {
        return mapRooms(topicRoomChatRepository.findActiveByRoomModeIn(List.of(RoomMode.LANGUAGE_PRACTICE)));
    }

    // ── Join / presence ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public LiveRoomResponse joinRoom(User user, String roomUuid) {
        groupService.joinChat(roomUuid, user);
        return toResponse(require(roomUuid), safeOnline());
    }

    @Override
    @Transactional(readOnly = true)
    public LiveRoomResponse enterRoom(User user, String roomUuid) {
        Chat chat = require(roomUuid);
        String key = key(roomUuid);
        try {
            redis.opsForSet().add(key, user.getUsername());
            redis.expire(key, PRESENCE_TTL);
        } catch (Exception e) {
            log.debug("Live room enter presence write skipped for {} / {}: {}",
                    user.getUsername(), key, e.getMessage());
        }
        broadcast("user_joined", roomUuid, user);
        return toResponse(chat, safeOnline());
    }

    @Override
    @Transactional(readOnly = true)
    public void leaveRoom(User user, String roomUuid) {
        require(roomUuid);
        try {
            redis.opsForSet().remove(key(roomUuid), user.getUsername());
        } catch (Exception e) {
            log.debug("Live room leave presence write skipped for {}: {}", user.getUsername(), e.getMessage());
        }
        broadcast("user_left", roomUuid, user);
    }

    // ── Language-room extras ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TranslateResponse translateInRoom(User user, String roomUuid, TranslateRequest req) {
        Chat chat = require(roomUuid);
        if (chat.getRoomMode() != RoomMode.LANGUAGE_PRACTICE) {
            throw new BadRequestException("Translation is only available in language-practice rooms", "TM_927");
        }
        return translationService.translate(user, req);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> suggestTopics(String roomUuid) {
        require(roomUuid); // validate the room exists / is not deleted
        List<String> out = new ArrayList<>();
        for (GameType type : STARTER_TYPES) {
            String prompt = GamePromptBank.promptAt(type, 0);
            if (prompt != null) {
                out.add(prompt);
            }
        }
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Reload a just-created room by its uuid string, or fail (TM_925).
     */
    private Chat reload(String uuid) {
        return chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Room not found", "TM_925"));
    }

    /**
     * Resolve a room uuid to a live (non-deleted) Chat, or fail (TM_926).
     */
    private Chat require(String roomUuid) {
        Chat chat;
        try {
            chat = chatRepository.findByUuid(UUID.fromString(roomUuid)).orElse(null);
        } catch (IllegalArgumentException e) {
            chat = null;
        }
        if (chat == null || chat.isDeleted()) {
            throw new NotFoundException("Room not found: " + roomUuid, "TM_926");
        }
        return chat;
    }

    private List<LiveRoomResponse> mapRooms(List<Chat> chats) {
        Set<String> online = safeOnline();
        List<LiveRoomResponse> out = new ArrayList<>();
        for (Chat chat : chats) {
            out.add(toResponse(chat, online));
        }
        return out;
    }

    private LiveRoomResponse toResponse(Chat chat, Set<String> online) {
        return LiveRoomResponse.builder()
                .id(chat.getUuid().toString())
                .name(chat.getName())
                .description(chat.getDescription())
                .category(chat.getCategory())
                .roomMode(chat.getRoomMode() != null ? chat.getRoomMode().name() : RoomMode.STANDARD.name())
                .memberCount(safeMemberCount(chat))
                .liveCount(liveCount(chat.getUuid().toString(), online))
                .createdAt(chat.getCreatedAt())
                .build();
    }

    /**
     * Cached member count for a chat, falling back to 0 on any lookup failure.
     */
    private int safeMemberCount(Chat chat) {
        try {
            return memberCountCache.get(chat);
        } catch (Exception e) {
            log.debug("Live room member-count lookup failed for {}: {}", chat.getUuid(), e.getMessage());
            return 0;
        }
    }

    /**
     * Number of the room's presence-set members that are currently online (fail-open ⇒ 0).
     */
    private int liveCount(String roomUuid, Set<String> online) {
        return (int) members(roomUuid).stream().filter(online::contains).count();
    }

    /**
     * Raw Redis presence set for a room; empty on read failure (fail-open).
     */
    private Set<String> members(String roomUuid) {
        try {
            Set<String> m = redis.opsForSet().members(key(roomUuid));
            return m != null ? m : Collections.emptySet();
        } catch (Exception e) {
            log.debug("Live room presence read skipped for {}: {}", roomUuid, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Global online-username set from {@link PresenceService}; empty on failure (fail-open).
     */
    private Set<String> safeOnline() {
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            return online != null ? online : Collections.emptySet();
        } catch (Exception e) {
            log.debug("Live room online-presence lookup failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Best-effort WebSocket broadcast to {@code /topic/chat/{uuid}/room} carrying the event, the
     * acting username and the online-intersected live count. Swallows any failure.
     */
    private void broadcast(String event, String roomUuid, User user) {
        try {
            long live = liveCount(roomUuid, safeOnline());
            messagingTemplate.convertAndSend("/topic/chat/" + roomUuid + "/room",
                    (Object) Map.of("event", event, "payload", Map.of(
                            "roomUuid", roomUuid,
                            "username", user.getUsername(),
                            "liveCount", live)));
        } catch (Exception e) {
            log.debug("Live room WS broadcast skipped for {} / {}: {}", event, roomUuid, e.getMessage());
        }
    }

    private static String key(String roomUuid) {
        return KEY_PREFIX + roomUuid;
    }
}
