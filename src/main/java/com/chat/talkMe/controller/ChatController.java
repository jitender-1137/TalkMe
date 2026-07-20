package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createChat(
            @Valid @RequestBody CreateChatRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = chatService.createChat(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Chat created successfully", "TM_120"));
    }

    @GetMapping
    public ResponseEntity<ResponseDto<List<ChatResponse>>> getChats(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ChatResponse> response = chatService.getChats(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

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
     */
    @GetMapping("/{id}/key")
    @PreAuthorize("hasAnyRole('USER','GUEST')")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.ChatKeyResponse>> getChatKey(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        com.chat.talkMe.dto.response.ChatKeyResponse response =
                chatService.getChatKey(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

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

    @DeleteMapping("/{id}/clear")
    public ResponseEntity<ResponseDto<Void>> clearChat(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.clearChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat cleared successfully", "TM_126"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deleteChat(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.deleteChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat deleted successfully", "TM_127"));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<ResponseDto<Void>> markRead(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markRead(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat read status updated", "TM_149"));
    }

    /** Mark a chat as UNREAD (sticky badge until the user opens it again). */
    @PutMapping("/{id}/unread")
    public ResponseEntity<ResponseDto<Void>> markUnread(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markUnread(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat marked unread", "TM_149"));
    }

    @PutMapping("/{id}/delivered")
    public ResponseEntity<ResponseDto<Void>> markDelivered(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markDelivered(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat delivery status updated", "TM_150"));
    }

    @PutMapping("/deliver-all")
    public ResponseEntity<ResponseDto<Void>> markAllChatsDelivered(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        chatService.markAllChatsDelivered(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All chats delivery status updated", "TM_151"));
    }
}
