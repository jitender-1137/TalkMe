package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FlirtModeResponse;

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

    /**
     * Opt {@code me} into flirt mode on this chat (upsert), recompute active, notify both.
     */
    FlirtModeResponse enable(User me, String chatUuid);

    /**
     * Opt {@code me} out of flirt mode on this chat (upsert), recompute active, notify both.
     */
    FlirtModeResponse disable(User me, String chatUuid);

    /**
     * Send a playful "blow a kiss" to the other participant of a 1:1 chat: a live, ephemeral
     * {@code flirt_kiss} event pushed to their {@code /user/queue/flirt-mode} that triggers a
     * full-screen heart animation. Requires Flirt Mode to be ACTIVE (both opted in); nothing is
     * persisted. Membership-guarded and PRIVATE-only, like every other flirt-mode operation.
     *
     * @param me       the authenticated sender (must be a chat member)
     * @param chatUuid the UUID of the PRIVATE chat
     * @throws com.neo.chat.exception.BadRequestException if the id is malformed, the chat is not a
     *                                                       1:1 PRIVATE chat, or Flirt Mode is not
     *                                                       active for both participants
     * @throws com.neo.chat.exception.NotFoundException   if no chat matches the UUID
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a chat member
     */
    void sendKiss(User me, String chatUuid);
}
