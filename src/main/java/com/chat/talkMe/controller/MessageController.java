package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.EditMessageRequest;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.response.MessagePageResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-chat message operations: send, page/sync/search history, edit/delete, self-destruct media
 * reveal/consume, pin/star, and reactions. Every route resolves {@code chatId} from the path and
 * enforces chat membership in the service layer.
 */
@RestController
@RequestMapping("/chats/{chatId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * Send a message to a chat (idempotent by client id; runs moderation/consent + durable fan-out).
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param request      validated message payload (content/type/attachment/reply/mentions/flags)
     * @param userDetails  the authenticated sender
     * @return the persisted (or ephemeral) message (TM_160)
     * @throws com.chat.talkMe.exception.NotFoundException          chat not found / deleted (TM_121)
     * @throws com.chat.talkMe.exception.ForbiddenException         not a member (TM_141), sender blocked
     *                                                              recipient (TM_142), friends-only recipient
     *                                                              (TM_143), or channel/mute send denied
     *                                                              (TM_294/TM_295)
     * @throws com.chat.talkMe.exception.TooManyRequestsException   slow mode active (TM_296)
     * @throws com.chat.talkMe.exception.ContentModerationException explicit content hard-blocked in a group
     */
    @PostMapping
    public ResponseEntity<ResponseDto<MessageResponse>> sendMessage(
            @PathVariable("chatId") String chatUuid,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.sendMessage(chatUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message sent successfully", "TM_160"));
    }

    /**
     * Fetch a page of chat history (newest first) using a sequence-number cursor.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param cursor       sequence number to page before (null = newest page)
     * @param limit        page size (defaults to 30, capped at 100)
     * @param userDetails  the authenticated caller
     * @return a page of messages with the next cursor and a hasMore flag
     * @throws com.chat.talkMe.exception.NotFoundException  chat not found (TM_121)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not a member (TM_141)
     */
    @GetMapping
    public ResponseEntity<ResponseDto<MessagePageResponse>> getMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "30") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessagePageResponse response = messageService.getMessages(chatUuid, cursor, limit, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Fetch messages created after a given sequence number (incremental catch-up sync).
     *
     * @param chatUuid       UUID of the chat (from the path)
     * @param afterSequence  return messages with a sequence number greater than this
     * @param userDetails    the authenticated caller
     * @return the newer messages wrapped in a success envelope
     * @throws com.chat.talkMe.exception.NotFoundException  chat not found (TM_121)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not a member (TM_141)
     */
    @GetMapping("/sync")
    public ResponseEntity<ResponseDto<List<MessageResponse>>> syncMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam("afterSequence") Long afterSequence,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<MessageResponse> response = messageService.getMessagesAfter(chatUuid, afterSequence, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Full-text search within a chat's messages.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param query        the search text
     * @param pageable     paging/sort (defaults to size 50, createdAt DESC)
     * @param userDetails  the authenticated caller
     * @return a page of matching messages wrapped in a success envelope
     * @throws com.chat.talkMe.exception.NotFoundException  chat not found (TM_121)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not a member (TM_141)
     */
    @GetMapping("/search")
    public ResponseEntity<ResponseDto<Page<MessageResponse>>> searchMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam("query") String query,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<MessageResponse> response = messageService.searchMessages(chatUuid, query, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Edit the caller's own text message (re-runs moderation, then broadcasts the update).
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to edit
     * @param request      body carrying the new {@code content}
     * @param userDetails  the authenticated sender
     * @return the updated message (TM_167)
     * @throws com.chat.talkMe.exception.NotFoundException          chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException         not a member (TM_141), message not in this
     *                                                              chat (TM_162), or not the sender (TM_163)
     * @throws com.chat.talkMe.exception.BadRequestException        message deleted (TM_164), non-text (TM_165),
     *                                                              or empty content (TM_166)
     * @throws com.chat.talkMe.exception.ContentModerationException edited text violates guidelines
     */
    @PatchMapping("/{messageId}")
    public ResponseEntity<ResponseDto<MessageResponse>> editMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @RequestBody EditMessageRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.editMessage(
                chatUuid, messageUuid, request.getContent(), userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message updated", "TM_167"));
    }

    /**
     * Delete a message: sender/group-admin deletes for everyone (tombstone + broadcast); a
     * recipient deletes it for themselves only.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to delete
     * @param userDetails  the authenticated caller
     * @return empty success envelope (TM_163)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_162)
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<ResponseDto<Void>> deleteMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.deleteMessage(chatUuid, messageUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message deleted successfully", "TM_163"));
    }

    // Receiver opens a self-destruct/view-once media → arms the timer (server-side) and
    // returns the message so the client can run its countdown. Only the receiver may arm.
    /**
     * Receiver opens a self-destruct/view-once media message, arming the server-side timer.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the self-destruct message
     * @param userDetails  the authenticated receiver
     * @return the message so the client can run its countdown
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), message not in this chat
     *                                                      (TM_162), or the sender tried to open it (TM_165)
     */
    @PostMapping("/{messageId}/reveal")
    public ResponseEntity<ResponseDto<MessageResponse>> revealSelfDestruct(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.revealSelfDestruct(chatUuid, messageUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    // Receiver finished viewing (countdown hit 0 / view-once closed) → destroy the media now.
    /**
     * Receiver finished viewing a self-destruct message, destroying the media now (no-op for sender).
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the self-destruct message
     * @param userDetails  the authenticated receiver
     * @return empty success envelope (TM_164)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_162)
     */
    @PostMapping("/{messageId}/consume")
    public ResponseEntity<ResponseDto<Void>> consumeSelfDestruct(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.consumeSelfDestruct(chatUuid, messageUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Media destroyed", "TM_164"));
    }

    // Pin / unpin a message (group admins per settings.whoCanPin).
    /**
     * Pin a message (group pinning gated by the chat's whoCanPin setting); broadcasts the change.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to pin
     * @param userDetails  the authenticated caller
     * @return the updated message (TM_287)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), message not in this chat
     *                                                      (TM_162), or lacks pin permission (TM_291)
     */
    @PostMapping("/{messageId}/pin")
    public ResponseEntity<ResponseDto<MessageResponse>> pinMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.setMessagePinned(chatUuid, messageUuid, true, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message pinned", "TM_287"));
    }

    /**
     * Unpin a message (group pinning gated by the chat's whoCanPin setting); broadcasts the change.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to unpin
     * @param userDetails  the authenticated caller
     * @return the updated message (TM_288)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), message not in this chat
     *                                                      (TM_162), or lacks pin permission (TM_291)
     */
    @DeleteMapping("/{messageId}/pin")
    public ResponseEntity<ResponseDto<MessageResponse>> unpinMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.setMessagePinned(chatUuid, messageUuid, false, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message unpinned", "TM_288"));
    }

    // Star / unstar (save) a message for the current user.
    /**
     * Star (save) a message for the current user; idempotent.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to star
     * @param userDetails  the authenticated caller
     * @return empty success envelope (TM_308)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_162)
     */
    @PostMapping("/{messageId}/star")
    public ResponseEntity<ResponseDto<Void>> starMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.setMessageStarred(chatUuid, messageUuid, true, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message starred", "TM_308"));
    }

    /**
     * Unstar (unsave) a message for the current user; a no-op if it was not starred.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message to unstar
     * @param userDetails  the authenticated caller
     * @return empty success envelope (TM_309)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_162)
     */
    @DeleteMapping("/{messageId}/star")
    public ResponseEntity<ResponseDto<Void>> unstarMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.setMessageStarred(chatUuid, messageUuid, false, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message unstarred", "TM_309"));
    }

    /**
     * Add an emoji reaction to a message (no-op if the caller already reacted with it); broadcasts.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message
     * @param request      validated body carrying the reaction {@code emoji}
     * @param userDetails  the authenticated caller
     * @return the updated message (TM_152)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_103)
     */
    @PostMapping("/{messageId}/reactions")
    public ResponseEntity<ResponseDto<MessageResponse>> reactToMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @Valid @RequestBody ReactToMessageRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.reactToMessage(chatUuid, messageUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reaction added successfully", "TM_152"));
    }

    /**
     * Remove the caller's emoji reaction from a message (no-op if absent); broadcasts the change.
     *
     * @param chatUuid     UUID of the chat (from the path)
     * @param messageUuid  UUID of the message
     * @param emoji        the reaction emoji to remove
     * @param userDetails  the authenticated caller
     * @return the updated message (TM_153)
     * @throws com.chat.talkMe.exception.NotFoundException  chat (TM_121) or message (TM_161) not found
     * @throws com.chat.talkMe.exception.ForbiddenException not a member (TM_141), or message not in this
     *                                                      chat (TM_103)
     */
    @DeleteMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<ResponseDto<MessageResponse>> removeReaction(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @PathVariable("emoji") String emoji,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.removeReaction(chatUuid, messageUuid, emoji, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reaction removed successfully", "TM_153"));
    }
}
