package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ConversationSummaryResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ConversationSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Our Story" conversation summary (feature #3.3). Read-only; gated by CONVERSATION_SUMMARY.
 * A dedicated controller (not ChatController) so the feature is self-contained.
 */
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ConversationSummaryController {

    private final ConversationSummaryService conversationSummaryService;

    @GetMapping("/{chatUuid}/summary")
    @PreAuthorize("@featureGuard.check('CONVERSATION_SUMMARY')")
    public ResponseEntity<ResponseDto<ConversationSummaryResponse>> summary(
            @PathVariable("chatUuid") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ConversationSummaryResponse summary =
                conversationSummaryService.summarize(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(summary));
    }
}
