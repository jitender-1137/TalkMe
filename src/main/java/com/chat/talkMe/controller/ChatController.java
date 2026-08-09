package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatKeyResponse;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Core conversation endpoints: create/list/fetch chats plus per-user chat management (archive, mute,
 * pin, clear, delete, read/unread, delivery). Most endpoints require chat membership, enforced in the
 * service layer.
 */
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * Creates a chat: a 1:1 PRIVATE chat when {@code recipientId} is set (reusing/reopening any existing
     * one between the two users), otherwise a legacy GROUP chat. Notifies the recipient over WebSocket.
     *
     * @param request     the create payload (recipientId for 1:1, or name + memberIds for a group)
     * @param userDetails the authenticated principal, who becomes a member (and admin/owner)
     * @return 200 with the created or reused {@link ChatResponse} (message code TM_120)
     * @throws com.chat.talkMe.exception.NotFoundException if a specified recipient user does not exist
     *                                                     (TM_064)
     */
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createChat(
            @Valid @RequestBody CreateChatRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = chatService.createChat(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Chat created successfully", "TM_120"));
    }

    /**
     * Lists the caller's chats, dropping message-less 1:1 chats, de-duplicating multiple 1:1 chats with
     * the same user (keeping the most recent), and sorting pinned-first then by last-message time.
     *
     * @param userDetails the authenticated principal whose conversations are returned
     * @return 200 with the caller's list of {@link ChatResponse}
     */
    @GetMapping
    public ResponseEntity<ResponseDto<List<ChatResponse>>> getChats(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ChatResponse> response = chatService.getChats(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Fetches a single chat the caller participates in (membership enforced to prevent leaking another
     * conversation's participant data by UUID).
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with the {@link ChatResponse}
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseDto<ChatResponse>> getChat(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = chatService.getChatByUuid(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Per-conversation encryption key — participant-only; held in client memory only.
     * GUESTS are allowed too: they are legitimate members of public rooms (and ephemeral
     * DMs), whose content is encrypted at rest, so they need the key to read it. Access is
     * still restricted to actual chat members by {@code getChatKey}'s membership check —
     * the role gate alone must not block guest room members (was 403-ing them).
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member of the chat
     * @return 200 with a {@link ChatKeyResponse}: {@code enabled=false} when encryption is off, otherwise
     * the base64 raw AES-256-GCM key with algo/version metadata
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @GetMapping("/{id}/key")
    @PreAuthorize("hasAnyRole('USER','GUEST')")
    public ResponseEntity<ResponseDto<ChatKeyResponse>> getChatKey(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatKeyResponse response =
                chatService.getChatKey(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Sets the caller's per-member archived flag for the chat.
     *
     * @param uuid        UUID of the chat, from the path
     * @param archive     true to archive, false to unarchive
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_122 archived / TM_123 unarchived)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/archive")
    public ResponseEntity<ResponseDto<Void>> archiveChat(
            @PathVariable("id") String uuid,
            @RequestParam("archive") boolean archive,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.archiveChat(uuid, userDetails.getUser(), archive);
        String msg = archive ? "Chat archived successfully" : "Chat unarchived successfully";
        String code = archive ? "TM_122" : "TM_123";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, code));
    }

    /**
     * Sets the caller's per-member muted flag for the chat.
     *
     * @param uuid        UUID of the chat, from the path
     * @param mute        true to mute, false to unmute
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_124 muted / TM_125 unmuted)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/mute")
    public ResponseEntity<ResponseDto<Void>> muteChat(
            @PathVariable("id") String uuid,
            @RequestParam("mute") boolean mute,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.muteChat(uuid, userDetails.getUser(), mute);
        String msg = mute ? "Chat muted successfully" : "Chat unmuted successfully";
        String code = mute ? "TM_124" : "TM_125";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, code));
    }

    /**
     * Sets the caller's per-member pinned flag for the chat.
     *
     * @param uuid        UUID of the chat, from the path
     * @param pin         true to pin, false to unpin
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_128 pinned / TM_129 unpinned)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/pin")
    public ResponseEntity<ResponseDto<Void>> pinChat(
            @PathVariable("id") String uuid,
            @RequestParam("pin") boolean pin,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.pinChat(uuid, userDetails.getUser(), pin);
        String msg = pin ? "Chat pinned successfully" : "Chat unpinned successfully";
        String code = pin ? "TM_128" : "TM_129";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, code));
    }

    /**
     * Clears the chat for the caller only by stamping their {@code clearedAt} so earlier messages no
     * longer appear in their view (does not delete messages for the other participants).
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_126)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @DeleteMapping("/{id}/clear")
    public ResponseEntity<ResponseDto<Void>> clearChat(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.clearChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat cleared successfully", "TM_126"));
    }

    /**
     * Deletes the chat: hard-deletes all messages, soft-deletes the chat and its members, and broadcasts
     * {@code chat_deleted}. For a multi-party chat this removes it for everyone and only the owner may do it.
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_127)
     * @throws com.chat.talkMe.exception.NotFoundException  if the chat does not exist (TM_121) or the caller
     *                                                      is not a member (TM_141)
     * @throws com.chat.talkMe.exception.ForbiddenException if a multi-party chat is deleted by a non-owner
     *                                                      (TM_291)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deleteChat(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.deleteChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat deleted successfully", "TM_127"));
    }

    /**
     * Marks the chat as read for the caller: clears any manual-unread flag, then advances the read
     * watermark (multi-party) or updates/creates READ receipts and broadcasts the change via the outbox.
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_149)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<ResponseDto<Void>> markRead(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markRead(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat read status updated", "TM_149"));
    }

    /**
     * Mark a chat as UNREAD (sticky badge until the user opens it again).
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_149)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/unread")
    public ResponseEntity<ResponseDto<Void>> markUnread(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markUnread(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat marked unread", "TM_149"));
    }

    /**
     * Marks the chat's messages as delivered for the caller: upgrades SENT receipts to DELIVERED (never
     * downgrading READ), creates missing DELIVERED receipts, and broadcasts via the outbox (suppressed for
     * ghost recipients).
     *
     * @param uuid        UUID of the chat, from the path
     * @param userDetails the authenticated principal, who must be a member
     * @return 200 with an empty payload (message code TM_150)
     * @throws com.chat.talkMe.exception.NotFoundException if the chat does not exist (TM_121) or the caller
     *                                                     is not a member (TM_141)
     */
    @PutMapping("/{id}/delivered")
    public ResponseEntity<ResponseDto<Void>> markDelivered(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markDelivered(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat delivery status updated", "TM_150"));
    }

    /**
     * Marks messages across all of the caller's chats as delivered (typically on reconnect), broadcasting
     * a {@code messages_delivered} event per updated chat unless the caller is in ghost mode.
     *
     * @param userDetails the authenticated principal whose chats are updated
     * @return 200 with an empty payload (message code TM_151)
     */
    @PutMapping("/deliver-all")
    public ResponseEntity<ResponseDto<Void>> markAllChatsDelivered(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markAllChatsDelivered(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All chats delivery status updated", "TM_151"));
    }
}
