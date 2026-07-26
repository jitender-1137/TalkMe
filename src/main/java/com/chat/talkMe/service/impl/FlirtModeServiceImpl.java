package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatFlirtMode;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.FlirtModeResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatFlirtModeRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.FlirtModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Per-chat Flirt Mode engine (feature FLIRT_MODE). See {@link FlirtModeService}.
 *
 * <p>Consent is keyed deterministically by {@code min(id)}/{@code max(id)} of the two participants
 * (see {@link ChatFlirtMode}), so the outcome is identical regardless of who calls. Only PRIVATE
 * (1:1) chats are eligible; group/room/stranger chats are rejected — the latter also protects the
 * stranger-anonymity invariant (this endpoint would otherwise leak partner-relative flags).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlirtModeServiceImpl implements FlirtModeService {

    /** Max attempts for the optimistic-lock retry when both participants toggle at once. */
    private static final int MAX_TOGGLE_ATTEMPTS = 3;

    private final ChatRepository chatRepository;
    private final ChatFlirtModeRepository flirtModeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    /** Self-proxy so the lazy row-create + the mutation transaction run through the proxy. */
    private final ObjectProvider<FlirtModeServiceImpl> self;

    /** Resolved, membership-verified context for a flirt-mode operation on a PRIVATE chat. */
    private record Ctx(Chat chat, long meId, User other, long lowUserId, long highUserId) {}

    /** A single after-commit WS delivery: the target username + their viewer-relative state. */
    private record Push(String username, FlirtModeResponse payload) {}

    /** Result of a committed consent mutation: the caller's response + both after-commit pushes. */
    private record ConsentResult(FlirtModeResponse response, Push mePush, Push otherPush) {}

    // ── Public API ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FlirtModeResponse getState(User me, String chatUuid) {
        Ctx ctx = resolve(me, chatUuid);
        ChatFlirtMode row = flirtModeRepository.findByChat(ctx.chat()).orElse(null);
        return toResponse(chatUuid, ctx, row);
    }

    @Override
    public FlirtModeResponse enable(User me, String chatUuid) {
        return setConsentWithRetry(me, chatUuid, true);
    }

    @Override
    public FlirtModeResponse disable(User me, String chatUuid) {
        return setConsentWithRetry(me, chatUuid, false);
    }

    // ── Core mutation ────────────────────────────────────────────────────────

    /**
     * Apply the caller's consent flag, then deliver the WS notifications ONLY after the mutation
     * transaction commits. This is intentionally NOT {@code @Transactional}: it drives the committed
     * {@link #applyConsentTx} through the self-proxy so the optimistic-lock check fires at the proxy
     * boundary (caught here), and retries when both participants toggle at once. Because the retry
     * re-reads the committed row, the loser's toggle is actually applied instead of 500ing; and
     * because the pushes run only after a successful commit, a rolled-back attempt never corrupts
     * either client's cached state.
     */
    private FlirtModeResponse setConsentWithRetry(User me, String chatUuid, boolean enabled) {
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < MAX_TOGGLE_ATTEMPTS; attempt++) {
            try {
                ConsentResult r = self.getObject().applyConsentTx(me, chatUuid, enabled);
                pushRaw(r.mePush(), chatUuid);
                pushRaw(r.otherPush(), chatUuid);
                return r.response();
            } catch (ObjectOptimisticLockingFailureException race) {
                last = race; // concurrent toggle won the version race — re-read and re-apply
                log.debug("[flirt-mode] optimistic-lock retry {} for chat {}", attempt + 1, chatUuid);
            }
        }
        throw last;
    }

    /**
     * The consent mutation itself, in its own committed transaction. Returns the caller's response
     * plus the two viewer-relative push payloads, computed here (in-tx, entity attached) so the
     * caller can deliver them safely after commit.
     */
    @Transactional
    public ConsentResult applyConsentTx(User me, String chatUuid, boolean enabled) {
        Ctx ctx = resolve(me, chatUuid);
        ChatFlirtMode row = getOrCreateRow(ctx);

        if (ctx.meId() == ctx.lowUserId()) {
            row.setEnabledByLow(enabled);
        } else {
            row.setEnabledByHigh(enabled);
        }
        row.recomputeActive();
        flirtModeRepository.save(row);

        FlirtModeResponse mine = responseFor(chatUuid, ctx.meId(), ctx.lowUserId(), row);
        Push mePush = new Push(me.getUsername(), mine);
        Push otherPush = ctx.other() != null
                ? new Push(ctx.other().getUsername(),
                           responseFor(chatUuid, ctx.other().getId(), ctx.lowUserId(), row))
                : null;
        return new ConsentResult(mine, mePush, otherPush);
    }

    /**
     * Fetch the chat's flirt-mode row, creating the single row lazily on first opt-in. Race-safe
     * on Postgres (mirrors {@code BucketListServiceImpl}): the INSERT runs in its OWN REQUIRES_NEW
     * transaction via the self-proxy, so a losing unique-constraint violation rolls back only that
     * inner tx and never poisons the caller's mutation transaction.
     */
    private ChatFlirtMode getOrCreateRow(Ctx ctx) {
        ChatFlirtMode existing = flirtModeRepository.findByChat(ctx.chat()).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return self.getObject().createRowInNewTx(ctx.chat().getId(), ctx.lowUserId(), ctx.highUserId());
        } catch (DataIntegrityViolationException raced) {
            return flirtModeRepository.findByChat(ctx.chat()).orElseThrow(() -> raced);
        }
    }

    /** Insert a fresh flirt-mode row in an isolated transaction (see getOrCreateRow). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatFlirtMode createRowInNewTx(Long chatId, Long lowUserId, Long highUserId) {
        Chat ref = chatRepository.getReferenceById(chatId);
        return flirtModeRepository.save(ChatFlirtMode.builder()
                .chat(ref)
                .lowUserId(lowUserId)
                .highUserId(highUserId)
                .enabledByLow(false)
                .enabledByHigh(false)
                .active(false)
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Load the chat, verify {@code me} is a member (IDOR guard), verify it is a PRIVATE 1:1 chat,
     * and resolve the other participant + deterministic low/high user ids.
     */
    private Ctx resolve(User me, String chatUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(chatUuid);
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        Chat chat = chatRepository.findByUuidWithMembers(uuid)
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_101"));

        if (chat.getChatType() != ChatType.PRIVATE) {
            throw new BadRequestException("Flirt mode is only available on 1:1 chats", "TM_830");
        }

        long meId = me.getId();
        boolean isMember = false;
        User other = null;
        for (ChatMember m : chat.getMembers()) {
            User u = m.getUser();
            if (u == null) continue;
            if (u.getId() == meId) {
                isMember = true;
            } else {
                other = u;
            }
        }
        if (!isMember) {
            throw new ForbiddenException("You are not a member of this chat", "TM_103");
        }
        if (other == null) {
            throw new BadRequestException("This chat has no other participant", "TM_831");
        }

        long otherId = other.getId();
        long lowUserId = Math.min(meId, otherId);
        long highUserId = Math.max(meId, otherId);
        return new Ctx(chat, meId, other, lowUserId, highUserId);
    }

    /** Build a viewer-relative response for the given context/row (null row → all-false). */
    private FlirtModeResponse toResponse(String chatUuid, Ctx ctx, ChatFlirtMode row) {
        return responseFor(chatUuid, ctx.meId(), ctx.lowUserId(), row);
    }

    /** Build a response relative to {@code viewerId}, given the low-id participant and the row. */
    private FlirtModeResponse responseFor(String chatUuid, long viewerId, long lowUserId, ChatFlirtMode row) {
        boolean lowEnabled = row != null && row.isEnabledByLow();
        boolean highEnabled = row != null && row.isEnabledByHigh();
        boolean active = row != null && row.isActive();

        boolean myEnabled = (viewerId == lowUserId) ? lowEnabled : highEnabled;
        boolean otherEnabled = (viewerId == lowUserId) ? highEnabled : lowEnabled;

        return FlirtModeResponse.builder()
                .chatUuid(chatUuid)
                .myEnabled(myEnabled)
                .otherEnabled(otherEnabled)
                .active(active)
                .build();
    }

    /** Deliver a pre-computed after-commit push to one participant (best-effort, fail-open). */
    private void pushRaw(Push push, String chatUuid) {
        if (push == null || push.username() == null) return;
        try {
            messagingTemplate.convertAndSendToUser(
                    push.username(),
                    "/queue/flirt-mode",
                    Map.of("event", "flirt_mode_changed", "payload", push.payload()));
        } catch (Exception e) {
            log.debug("[flirt-mode] push failed for chat {}: {}", chatUuid, e.getMessage());
        }
    }
}
