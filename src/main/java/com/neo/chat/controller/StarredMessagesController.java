package com.neo.chat.controller;

import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The current user's starred (saved) messages across all their chats.
 */
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class StarredMessagesController {

    private final MessageService messageService;

    /**
     * The caller's starred messages across all chats, newest first (each flagged starred).
     *
     * @param limit       max messages to return (default 100; clamped to 1..200, non-positive → 100)
     * @param userDetails the authenticated caller
     * @return the caller's starred messages in a success envelope
     */
    @GetMapping("/starred")
    public ResponseEntity<ResponseDto<List<MessageResponse>>> getStarred(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<MessageResponse> response = messageService.getStarredMessages(userDetails.getUser(), limit);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
