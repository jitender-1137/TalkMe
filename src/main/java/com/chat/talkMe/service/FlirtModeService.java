package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.FlirtModeResponse;

/**
 * Per-chat Flirt Mode engine (feature FLIRT_MODE). A revertible, mutual toggle on a single
 * 1:1 (PRIVATE) chat: only ACTIVE when BOTH participants have enabled it, and reverted the
 * moment either disables. All operations are membership-guarded (IDOR-safe) and restricted to
 * PRIVATE chats. Every mutation pushes each participant their own viewer-relative
 * {@link FlirtModeResponse} over {@code /user/queue/flirt-mode}.
 */
public interface FlirtModeService {

    /**
     * Current viewer-relative state for {@code me}. Membership-guarded; never creates a row
     * (an absent row reads as all-false).
     */
    FlirtModeResponse getState(User me, String chatUuid);

    /** Opt {@code me} into flirt mode on this chat (upsert), recompute active, notify both. */
    FlirtModeResponse enable(User me, String chatUuid);

    /** Opt {@code me} out of flirt mode on this chat (upsert), recompute active, notify both. */
    FlirtModeResponse disable(User me, String chatUuid);
}
