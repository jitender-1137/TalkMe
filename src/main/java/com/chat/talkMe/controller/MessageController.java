package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.MessagePageResponse;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chats/{chatId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<ResponseDto<MessageResponse>> sendMessage(
            @PathVariable("chatId") String chatUuid,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.sendMessage(chatUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message sent successfully", "TM_160"));
    }

    @GetMapping
    public ResponseEntity<ResponseDto<MessagePageResponse>> getMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "30") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessagePageResponse response = messageService.getMessages(chatUuid, cursor, limit, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/sync")
    public ResponseEntity<ResponseDto<java.util.List<MessageResponse>>> syncMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam("afterSequence") Long afterSequence,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        java.util.List<MessageResponse> response = messageService.getMessagesAfter(chatUuid, afterSequence, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/search")
    public ResponseEntity<ResponseDto<Page<MessageResponse>>> searchMessages(
            @PathVariable("chatId") String chatUuid,
            @RequestParam("query") String query,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<MessageResponse> response = messageService.searchMessages(chatUuid, query, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

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
    @PostMapping("/{messageId}/reveal")
    public ResponseEntity<ResponseDto<MessageResponse>> revealSelfDestruct(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.revealSelfDestruct(chatUuid, messageUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    // Receiver finished viewing (countdown hit 0 / view-once closed) → destroy the media now.
    @PostMapping("/{messageId}/consume")
    public ResponseEntity<ResponseDto<Void>> consumeSelfDestruct(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.consumeSelfDestruct(chatUuid, messageUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Media destroyed", "TM_164"));
    }

    // Pin / unpin a message (group admins per settings.whoCanPin).
    @PostMapping("/{messageId}/pin")
    public ResponseEntity<ResponseDto<MessageResponse>> pinMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.setMessagePinned(chatUuid, messageUuid, true, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message pinned", "TM_287"));
    }

    @DeleteMapping("/{messageId}/pin")
    public ResponseEntity<ResponseDto<MessageResponse>> unpinMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.setMessagePinned(chatUuid, messageUuid, false, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Message unpinned", "TM_288"));
    }

    // Star / unstar (save) a message for the current user.
    @PostMapping("/{messageId}/star")
    public ResponseEntity<ResponseDto<Void>> starMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.setMessageStarred(chatUuid, messageUuid, true, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message starred", "TM_308"));
    }

    @DeleteMapping("/{messageId}/star")
    public ResponseEntity<ResponseDto<Void>> unstarMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        messageService.setMessageStarred(chatUuid, messageUuid, false, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message unstarred", "TM_309"));
    }

    @PostMapping("/{messageId}/reactions")
    public ResponseEntity<ResponseDto<MessageResponse>> reactToMessage(
            @PathVariable("chatId") String chatUuid,
            @PathVariable("messageId") String messageUuid,
            @Valid @RequestBody ReactToMessageRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MessageResponse response = messageService.reactToMessage(chatUuid, messageUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reaction added successfully", "TM_152"));
    }

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
