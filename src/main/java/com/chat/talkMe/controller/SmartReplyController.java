package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.SmartReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/chats/{chatId}/smart-replies")
@RequiredArgsConstructor
public class SmartReplyController {

    private final SmartReplyService smartReplyService;

    /**
     * Contextual reply suggestions for the current user in this chat. Returns an
     * empty list when the feature is disabled, the sidecar is unreachable, or
     * it's not the user's turn — the client then falls back to local chips.
     */
    @GetMapping
    public ResponseEntity<ResponseDto<List<String>>> getSuggestions(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<String> suggestions = smartReplyService.suggestReplies(chatUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(suggestions));
    }
}
