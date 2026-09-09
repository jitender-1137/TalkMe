package com.neo.chat.controller;

import com.neo.chat.dto.request.FeedbackRequest;
import com.neo.chat.dto.response.FeedbackResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Captures user-submitted feedback (rating, reason, comment). Served at {@code /feedback};
 * every route is gated by {@code hasRole('USER')} at class level.
 */
@RestController
@Tag(name = "Feedback", description = "Captures user-submitted feedback (rating, reason, comment)")
@RequestMapping("/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * Stores a feedback submission from the current user (rating clamped to 0–5).
     *
     * @param request     the feedback payload (rating, reason, comment, type, context, platform)
     * @param userDetails the authenticated user submitting the feedback
     * @return 200 with the persisted feedback and success code TM_310
     * @throws com.neo.chat.exception.BadRequestException if rating, reason and comment are all
     *                                                       empty (nothing to record)
     */
    @Operation(summary = "Stores a feedback submission from the current user (rating clamped to 0–5)")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<FeedbackResponse>> submit(
            @Valid @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FeedbackResponse response = feedbackService.submit(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Thanks for your feedback!", "TM_310"));
    }
}
