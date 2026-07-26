package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MusicPlayRequest;
import com.chat.talkMe.dto.response.MusicSessionState;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.MusicSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-ephemeral implementation of the shared per-chat music session (feature #17).
 *
 * <p>All playback state lives in the Redis string {@code music:session:{chatId}} (JSON, ~6h TTL);
 * there is no DB table, so the class is {@link Transactional} {@code readOnly=true} — the only DB
 * access is the read-only membership guard. Every Redis touch fails open (logs at debug, never
 * blocks the request), and every mutation broadcasts on {@code /topic/chat/{chatId}/music}
 * (already membership-authorized by the WS channel interceptor).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MusicSessionServiceImpl implements MusicSessionService {

    private static final String KEY_PREFIX = "music:session:";
    private static final Duration TTL = Duration.ofHours(6);
    private static final int MAX_EMOJI_LEN = 24;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    /** IDOR guard: the caller must be a member of the chat the session runs in. */
    private void requireChatMember(User user, String chatId) {
        boolean member;
        try {
            member = chatRepository.findByUuid(UUID.fromString(chatId))
                    .flatMap(c -> chatMemberRepository.findByChatAndUser(c, user))
                    .isPresent();
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        if (!member) {
            throw new ForbiddenException("You are not a member of this chat", "TM_103");
        }
    }

    @Override
    public MusicSessionState getSession(User user, String chatId) {
        requireChatMember(user, chatId);
        long now = System.currentTimeMillis();
        MusicSessionState state = readState(chatId);
        if (state == null) {
            // No live session: still hand back a clock reference + a not-playing shell.
            return MusicSessionState.builder()
                    .playing(false)
                    .positionSec(0)
                    .updatedAtEpochMs(now)
                    .serverTimeEpochMs(now)
                    .build();
        }
        state.setServerTimeEpochMs(now);
        return state;
    }

    @Override
    public MusicSessionState play(User user, String chatId, MusicPlayRequest request) {
        requireChatMember(user, chatId);
        if (request == null || request.getUrl() == null || request.getUrl().isBlank()) {
            throw new BadRequestException("A playable track url is required", "TM_800");
        }
        long now = System.currentTimeMillis();
        MusicSessionState existing = readState(chatId);

        // Start position: explicit wins; else resume the current playhead for the same track; else 0.
        double position;
        if (request.getPositionSec() != null) {
            position = Math.max(0, request.getPositionSec());
        } else if (existing != null && request.getTrackId() != null
                && request.getTrackId().equals(existing.getTrackId())) {
            position = Math.max(0, existing.getPositionSec());
        } else {
            position = 0;
        }

        MusicSessionState state = MusicSessionState.builder()
                .trackId(request.getTrackId())
                .url(request.getUrl().trim())
                .title(request.getTitle())
                .artist(request.getArtist())
                .artworkUrl(request.getArtworkUrl())
                .positionSec(position)
                .playing(true)
                .updatedAtEpochMs(now)
                .hostUsername(user.getUsername())
                .serverTimeEpochMs(now)
                .build();

        writeState(chatId, state);
        broadcast(chatId, "music_play", state);
        return state;
    }

    @Override
    public MusicSessionState pause(User user, String chatId, Double positionSec) {
        requireChatMember(user, chatId);
        MusicSessionState state = requireLiveSession(chatId);
        long now = System.currentTimeMillis();
        if (positionSec != null) {
            state.setPositionSec(Math.max(0, positionSec));
        }
        state.setPlaying(false);
        state.setHostUsername(user.getUsername());
        state.setUpdatedAtEpochMs(now);
        state.setServerTimeEpochMs(now);
        writeState(chatId, state);
        broadcast(chatId, "music_pause", state);
        return state;
    }

    @Override
    public MusicSessionState seek(User user, String chatId, Double positionSec) {
        requireChatMember(user, chatId);
        if (positionSec == null || positionSec < 0) {
            throw new BadRequestException("positionSec is required", "TM_801");
        }
        MusicSessionState state = requireLiveSession(chatId);
        long now = System.currentTimeMillis();
        state.setPositionSec(positionSec);
        state.setHostUsername(user.getUsername());
        state.setUpdatedAtEpochMs(now);
        state.setServerTimeEpochMs(now);
        writeState(chatId, state);
        broadcast(chatId, "music_seek", state);
        return state;
    }

    @Override
    public MusicSessionState react(User user, String chatId, String emoji) {
        requireChatMember(user, chatId);
        if (emoji == null || emoji.isBlank()) {
            throw new BadRequestException("emoji is required", "TM_803");
        }
        String reaction = emoji.trim();
        if (reaction.length() > MAX_EMOJI_LEN) {
            reaction = reaction.substring(0, MAX_EMOJI_LEN);
        }
        MusicSessionState state = requireLiveSession(chatId);
        long now = System.currentTimeMillis();
        state.setServerTimeEpochMs(now);
        // A reaction doesn't change playback; just refresh the TTL so the session stays alive.
        writeState(chatId, state);
        // Broadcast the reaction plus the current state so late/laggy clients still have context.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("emoji", reaction);
        payload.put("by", user.getUsername());
        payload.put("serverTimeEpochMs", now);
        payload.put("session", state);
        broadcast(chatId, "music_react", payload);
        return state;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MusicSessionState requireLiveSession(String chatId) {
        MusicSessionState state = readState(chatId);
        if (state == null) {
            throw new BadRequestException("No active music session", "TM_802");
        }
        return state;
    }

    private MusicSessionState readState(String chatId) {
        try {
            String cached = redis.opsForValue().get(key(chatId));
            if (cached != null) {
                return objectMapper.readValue(cached, MusicSessionState.class);
            }
        } catch (Exception e) {
            log.debug("MusicSession read skipped for {}: {}", chatId, e.getMessage());
        }
        return null;
    }

    private void writeState(String chatId, MusicSessionState state) {
        try {
            redis.opsForValue().set(key(chatId), objectMapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.debug("MusicSession write skipped for {}: {}", chatId, e.getMessage());
        }
    }

    private void broadcast(String chatId, String event, Object payload) {
        try {
            messagingTemplate.convertAndSend("/topic/chat/" + chatId + "/music",
                    (Object) Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("MusicSession WS broadcast skipped for {} / {}: {}", event, chatId, e.getMessage());
        }
    }

    private static String key(String chatId) {
        return KEY_PREFIX + chatId;
    }
}
