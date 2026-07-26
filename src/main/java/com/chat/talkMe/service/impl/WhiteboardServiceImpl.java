package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.WhiteboardStrokeRequest;
import com.chat.talkMe.dto.response.WhiteboardOp;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.WhiteboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-backed Shared Whiteboard (feature SHARED_WHITEBOARD).
 *
 * <p>Two key families per chat, both expiring after 6h of inactivity:
 * <ul>
 *   <li>{@code wb:ops:{chatUuid}} — a capped list (last {@value #MAX_OPS} ops) of op JSON.</li>
 *   <li>{@code wb:seq:{chatUuid}} — an INCR counter giving each op a monotonic sequence number.</li>
 * </ul>
 *
 * <p>The server is the single source of truth: it stamps {@code seq}/{@code ts}, appends to the
 * op-log, then re-broadcasts the op on the existing chat topic
 * ({@code /topic/chat/{chatUuid}/messages}).
 *
 * <p>All Redis operations are fail-open (logged + swallowed) so a Redis blip never fails a draw.
 * The ONLY hard failure is the membership (IDOR) check, which must reject non-members.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhiteboardServiceImpl implements WhiteboardService {

    private static final String OPS_KEY_PREFIX = "wb:ops:";
    private static final String SEQ_KEY_PREFIX = "wb:seq:";
    private static final Duration TTL = Duration.ofHours(6);
    private static final long MAX_OPS = 1000;
    private static final int MAX_POINTS = 2000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public List<WhiteboardOp> getBoard(User me, String chatUuid) {
        requireChatMember(me, chatUuid);
        List<WhiteboardOp> ops = new ArrayList<>();
        try {
            List<String> raw = redis.opsForList().range(opsKey(chatUuid), 0, -1);
            if (raw != null) {
                for (String json : raw) {
                    try {
                        ops.add(objectMapper.readValue(json, WhiteboardOp.class));
                    } catch (Exception parse) {
                        log.debug("[whiteboard] skipping unparseable op for {}: {}", chatUuid, parse.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[whiteboard] getBoard read failed for {}: {}", chatUuid, e.getMessage());
        }
        return ops;
    }

    @Override
    public WhiteboardOp addStroke(User me, WhiteboardStrokeRequest req) {
        requireChatMember(me, req.getChatUuid());

        List<double[]> points = req.getPoints();
        if (points != null) {
            if (points.size() > MAX_POINTS) {
                throw new BadRequestException("A stroke may contain at most " + MAX_POINTS + " points", "TM_821");
            }
            // Bound each point to an [x, y] pair. Without this a caller could pass 2000 arrays of
            // arbitrary length (or one giant array) that pass the count cap but store/rebroadcast
            // tens of MB — a Redis-memory + peer-bandwidth amplification vector.
            for (double[] p : points) {
                if (p == null || p.length != 2) {
                    throw new BadRequestException("Each point must be an [x, y] pair", "TM_821");
                }
            }
        }

        String chatUuid = req.getChatUuid();
        WhiteboardOp op = WhiteboardOp.builder()
                .seq(nextSeq(chatUuid))
                .type("stroke")
                .authorUuid(me.getUuid().toString())
                .color(req.getColor())
                .size(req.getSize())
                .tool(req.getTool())
                .points(points)
                .ts(Instant.now().toEpochMilli())
                .build();

        pushOp(chatUuid, op);
        broadcast(chatUuid, "whiteboard_stroke", op);
        return op;
    }

    @Override
    public void clear(User me, String chatUuid) {
        requireChatMember(me, chatUuid);

        WhiteboardOp op = WhiteboardOp.builder()
                .seq(nextSeq(chatUuid))
                .type("clear")
                .authorUuid(me.getUuid().toString())
                .ts(Instant.now().toEpochMilli())
                .build();

        // Drop the accumulated strokes, then seed the fresh log with the clear marker so a late
        // joiner replaying getBoard sees an empty board (fail-open on every Redis call).
        try {
            redis.delete(opsKey(chatUuid));
        } catch (Exception e) {
            log.warn("[whiteboard] clear delete failed for {}: {}", chatUuid, e.getMessage());
        }
        pushOp(chatUuid, op);
        broadcast(chatUuid, "whiteboard_clear", op);
    }

    @Override
    public WhiteboardOp undo(User me, String chatUuid) {
        requireChatMember(me, chatUuid);

        WhiteboardOp op = WhiteboardOp.builder()
                .seq(nextSeq(chatUuid))
                .type("undo")
                .authorUuid(me.getUuid().toString())
                .ts(Instant.now().toEpochMilli())
                .build();

        // Keep it simple: append the undo marker and let clients reconcile (remove the author's
        // last stroke). getBoard replays in order, so a persisted undo op is harmless.
        pushOp(chatUuid, op);
        broadcast(chatUuid, "whiteboard_undo", op);
        return op;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * IDOR guard: the caller must be an ACTIVE member of the chat. Hard-fails (never fail-open).
     * A bare {@code findByChatAndUser(...).isPresent()} would let removed/left/banned users through:
     * removal/leave only set {@code leftAt}, a ban sets {@code isBanned}, and a deleted 1:1 chat
     * soft-deletes the member rows — the row stays queryable. Mirror the canonical active-member
     * predicate ({@code !isDeleted && leftAt == null && !isBanned}, plus a live chat).
     */
    private void requireChatMember(User user, String chatUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(chatUuid);
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        var chat = chatRepository.findByUuid(uuid)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_103"));
        chatMemberRepository.findByChatAndUser(chat, user)
                .filter(m -> !m.isDeleted() && m.getLeftAt() == null && !m.isBanned())
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_103"));
    }

    /** Atomically allocate the next per-chat sequence number. Fail-open to a time-based fallback. */
    private long nextSeq(String chatUuid) {
        try {
            String seqKey = seqKey(chatUuid);
            Long val = redis.opsForValue().increment(seqKey);
            redis.expire(seqKey, TTL);
            if (val != null) {
                return val;
            }
        } catch (Exception e) {
            log.warn("[whiteboard] seq increment failed for {}: {}", chatUuid, e.getMessage());
        }
        return Instant.now().toEpochMilli();
    }

    /** Append the op JSON to the capped op-log and refresh its TTL. Fail-open. */
    private void pushOp(String chatUuid, WhiteboardOp op) {
        try {
            String opsKey = opsKey(chatUuid);
            String json = objectMapper.writeValueAsString(op);
            redis.opsForList().rightPush(opsKey, json);
            redis.opsForList().trim(opsKey, -MAX_OPS, -1);
            redis.expire(opsKey, TTL);
        } catch (Exception e) {
            log.warn("[whiteboard] pushOp failed for {}: {}", chatUuid, e.getMessage());
        }
    }

    /** Re-broadcast an op on the existing chat topic. Fail-open. */
    private void broadcast(String chatUuid, String event, WhiteboardOp payload) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + chatUuid + "/messages",
                    (Object) Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("[whiteboard] broadcast {} failed for {}: {}", event, chatUuid, e.getMessage());
        }
    }

    private static String opsKey(String chatUuid) {
        return OPS_KEY_PREFIX + chatUuid;
    }

    private static String seqKey(String chatUuid) {
        return SEQ_KEY_PREFIX + chatUuid;
    }
}
