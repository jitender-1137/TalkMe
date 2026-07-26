package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.FeedbackRequest;
import com.chat.talkMe.dto.response.FeedbackResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<ResponseDto<FeedbackResponse>> submit(
            @Valid @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FeedbackResponse response = feedbackService.submit(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Thanks for your feedback!", "TM_310"));
    }
}
