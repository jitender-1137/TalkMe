package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FlirtModeResponse;
import com.neo.chat.service.FlirtModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Per-chat Flirt Mode engine (feature FLIRT_MODE). See {@link FlirtModeService}.
 *
 * <p>Thin facade over {@link FlirtModeConsentTx}, which owns the transactional consent/row logic.
 * This class is intentionally NOT {@code @Transactional}: it drives the committed
 * {@link FlirtModeConsentTx#applyConsentTx} <b>cross-bean</b> (through a real Spring proxy) so the
 * optimistic-lock check fires at the proxy boundary — caught here and retried — and delivers the WS
 * notifications ONLY after that mutation transaction commits.
 *
 * <p>Consent is keyed deterministically by {@code min(id)}/{@code max(id)} of the two participants
 * (see {@link com.neo.chat.domain.ChatFlirtMode}), so the outcome is identical regardless of who
 * calls. Only PRIVATE (1:1) chats are eligible; group/room/stranger chats are rejected — the latter
 * also protects the stranger-anonymity invariant (this endpoint would otherwise leak partner-relative
 * flags).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlirtModeServiceImpl implements FlirtModeService {

    /**
     * Max attempts for the optimistic-lock retry when both participants toggle at once.
     */
    private static final int MAX_TOGGLE_ATTEMPTS = 3;

    private final SimpMessagingTemplate messagingTemplate;
    /** Cross-bean collaborator owning the transactional consent/row logic (real proxy per attempt). */
    private final FlirtModeConsentTx consentTx;

    // ── Public API ───────────────────────────────────────────────────────────

    @Override
    public FlirtModeResponse getState(User me, String chatUuid) {
        return consentTx.getState(me, chatUuid);
    }

    @Override
    public FlirtModeResponse enable(User me, String chatUuid) {
        return setConsentWithRetry(me, chatUuid, true);
    }

    @Override
    public FlirtModeResponse disable(User me, String chatUuid) {
        return setConsentWithRetry(me, chatUuid, false);
    }

    @Override
    public void sendKiss(User me, String chatUuid) {
        String other = consentTx.resolveKissPartnerOrThrow(me, chatUuid);
        if (other == null) {
            return; // no reachable partner — nothing to deliver
        }
        // Ephemeral live nudge (NOT persisted). Reuses the per-user flirt-mode queue, whose client
        // handler fans events out by name — the `flirt_kiss` handler plays the heart animation. The
        // payload carries `chatId` (not `chatUuid`) so it never collides with the state cache write.
        try {
            messagingTemplate.convertAndSendToUser(
                    other,
                    "/queue/flirt-mode",
                    Map.of("event", "flirt_kiss",
                            "payload", Map.of(
                                    "chatId", chatUuid,
                                    "fromName", me.getName() != null ? me.getName() : "")));
        } catch (Exception e) {
            log.debug("[flirt-mode] kiss push failed for chat {}: {}", chatUuid, e.getMessage());
        }
    }

    // ── Core mutation ────────────────────────────────────────────────────────

    /**
     * Apply the caller's consent flag, then deliver the WS notifications ONLY after the mutation
     * transaction commits. This is intentionally NOT {@code @Transactional}: it drives the committed
     * {@link FlirtModeConsentTx#applyConsentTx} through the real cross-bean proxy so the
     * optimistic-lock check fires at the proxy boundary (caught here), and retries when both
     * participants toggle at once. Because the retry re-reads the committed row, the loser's toggle
     * is actually applied instead of 500ing; and because the pushes run only after a successful
     * commit, a rolled-back attempt never corrupts either client's cached state.
     */
    private FlirtModeResponse setConsentWithRetry(User me, String chatUuid, boolean enabled) {
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < MAX_TOGGLE_ATTEMPTS; attempt++) {
            try {
                FlirtModeConsentTx.ConsentResult r = consentTx.applyConsentTx(me, chatUuid, enabled);
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
     * Deliver a pre-computed after-commit push to one participant (best-effort, fail-open).
     */
    private void pushRaw(FlirtModeConsentTx.Push push, String chatUuid) {
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
