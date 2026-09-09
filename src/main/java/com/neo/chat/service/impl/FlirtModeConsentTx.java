package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatFlirtMode;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FlirtModeResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatFlirtModeRepository;
import com.neo.chat.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional consent/row collaborator for {@link FlirtModeServiceImpl} (feature FLIRT_MODE).
 *
 * <p>Extracted into its own bean so {@link #applyConsentTx} is invoked cross-bean through a real
 * Spring proxy rather than via a same-bean self-proxy (BootUI ARCH-SPRING-004). The facade's retry
 * loop calls this bean once per attempt, so EACH attempt runs in its OWN committed transaction; an
 * {@code ObjectOptimisticLockingFailureException} rolls back only that attempt and the facade
 * re-reads+re-applies. The transaction boundary is identical to the previous self-proxy design.
 *
 * <p>Consent is keyed deterministically by {@code min(id)}/{@code max(id)} of the two participants
 * (see {@link ChatFlirtMode}), so the outcome is identical regardless of who calls. Only PRIVATE
 * (1:1) chats are eligible; group/room/stranger chats are rejected.
 */
@Component
@RequiredArgsConstructor
class FlirtModeConsentTx {

    private final ChatRepository chatRepository;
    private final ChatFlirtModeRepository flirtModeRepository;
    /** Isolated-transaction row insert (own bean so the REQUIRES_NEW insert crosses a real proxy). */
    private final FlirtModeRowCreator rowCreator;

    /**
     * Resolved, membership-verified context for a flirt-mode operation on a PRIVATE chat.
     */
    private record Ctx(Chat chat, long meId, User other, long lowUserId, long highUserId) {
    }

    /**
     * A single after-commit WS delivery: the target username + their viewer-relative state.
     */
    record Push(String username, FlirtModeResponse payload) {
    }

    /**
     * Result of a committed consent mutation: the caller's response + both after-commit pushes.
     */
    record ConsentResult(FlirtModeResponse response, Push mePush, Push otherPush) {
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Read-only viewer-relative state for the caller.
     */
    @Transactional(readOnly = true)
    public FlirtModeResponse getState(User me, String chatUuid) {
        Ctx ctx = resolve(me, chatUuid);
        ChatFlirtMode row = flirtModeRepository.findByChat(ctx.chat()).orElse(null);
        return toResponse(chatUuid, ctx, row);
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
     * Resolve + active-check for a "blow a kiss" request. Verifies membership/eligibility and that
     * flirt mode is active for BOTH participants; returns the other participant's username (or
     * {@code null} when there is no reachable partner). Does NO messaging — the facade delivers the
     * ephemeral nudge after this returns.
     */
    @Transactional(readOnly = true)
    public String resolveKissPartnerOrThrow(User me, String chatUuid) {
        Ctx ctx = resolve(me, chatUuid);
        boolean active = flirtModeRepository.findByChat(ctx.chat())
                .map(ChatFlirtMode::isActive)
                .orElse(false);
        if (!active) {
            throw new BadRequestException("Flirt Mode must be active for both of you to blow a kiss", "TM_834");
        }
        return ctx.other() != null ? ctx.other().getUsername() : null;
    }

    // ── Core row lifecycle ─────────────────────────────────────────────────────

    /**
     * Fetch the chat's flirt-mode row, creating the single row lazily on first opt-in. Race-safe
     * on Postgres (mirrors {@code BucketListServiceImpl}): the INSERT runs in its OWN REQUIRES_NEW
     * transaction via {@link FlirtModeRowCreator}, so a losing unique-constraint violation rolls
     * back only that inner tx and never poisons the caller's mutation transaction.
     */
    private ChatFlirtMode getOrCreateRow(Ctx ctx) {
        ChatFlirtMode existing = flirtModeRepository.findByChat(ctx.chat()).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return rowCreator.createInNewTx(ctx.chat().getId(), ctx.lowUserId(), ctx.highUserId());
        } catch (DataIntegrityViolationException raced) {
            return flirtModeRepository.findByChat(ctx.chat()).orElseThrow(() -> raced);
        }
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

    /**
     * Build a viewer-relative response for the given context/row (null row → all-false).
     */
    private FlirtModeResponse toResponse(String chatUuid, Ctx ctx, ChatFlirtMode row) {
        return responseFor(chatUuid, ctx.meId(), ctx.lowUserId(), row);
    }

    /**
     * Build a response relative to {@code viewerId}, given the low-id participant and the row.
     */
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
}
