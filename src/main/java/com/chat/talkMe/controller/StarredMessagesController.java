package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The current user's starred (saved) messages across all their chats. */
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class StarredMessagesController {

    private final MessageService messageService;

    @GetMapping("/starred")
    public ResponseEntity<ResponseDto<List<MessageResponse>>> getStarred(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<MessageResponse> response = messageService.getStarredMessages(userDetails.getUser(), limit);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
