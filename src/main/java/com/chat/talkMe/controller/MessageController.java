package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
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
    public ResponseEntity<ResponseDto<Page<MessageResponse>>> getMessages(
            @PathVariable("chatId") String chatUuid,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<MessageResponse> response = messageService.getMessages(chatUuid, pageable, userDetails.getUser());
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
