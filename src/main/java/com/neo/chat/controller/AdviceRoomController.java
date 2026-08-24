package com.neo.chat.controller;

import com.neo.chat.dto.request.AskQuestionRequest;
import com.neo.chat.dto.request.ReplyRequest;
import com.neo.chat.dto.response.AdviceQuestionPageResponse;
import com.neo.chat.dto.response.AdviceQuestionResponse;
import com.neo.chat.dto.response.AdviceReplyResponse;
import com.neo.chat.dto.response.AdviceThreadResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.AdviceCategory;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.AdviceRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous Advice Rooms (feature ADVICE_ROOMS). Every route is gated per-method by the
 * ADVICE_ROOMS entitlement.
 *
 * <p>ANONYMITY: no endpoint here ever discloses who asked a question or wrote a reply — the author
 * is stored for moderation only and the service's DTO mapping omits it entirely.
 */
@RestController
@RequestMapping("/advice")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class AdviceRoomController {

    private final AdviceRoomService adviceRoomService;

    /**
     * Post an anonymous question.
     *
     * @throws com.neo.chat.exception.BadRequestException        on a blank title/body (TM_850)
     * @throws com.neo.chat.exception.ContentModerationException if the text fails moderation
     * @throws com.neo.chat.exception.TooManyRequestsException   if the rate cap is exceeded (TM_851)
     */
    @PostMapping("/questions")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<AdviceQuestionResponse>> ask(
            @Valid @RequestBody AskQuestionRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AdviceQuestionResponse response = adviceRoomService.askQuestion(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Question posted", "TM_000"));
    }

    /**
     * List questions (optionally filtered by category), newest first, cursor-paginated.
     */
    @GetMapping("/questions")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<AdviceQuestionPageResponse>> list(
            @RequestParam(name = "category", required = false) AdviceCategory category,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AdviceQuestionPageResponse page =
                adviceRoomService.listQuestions(userDetails.getUser(), category, cursor, limit);
        return ResponseEntity.ok(SuccessResponseDto.success(page));
    }

    /**
     * A single question with its anonymised replies.
     *
     * @throws com.neo.chat.exception.BadRequestException if the UUID is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the question is missing (TM_853)
     */
    @GetMapping("/questions/{uuid}")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<AdviceThreadResponse>> getQuestion(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AdviceThreadResponse thread = adviceRoomService.getQuestion(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(thread));
    }

    /**
     * Post an anonymous reply to a question, optionally threaded under a parent reply.
     *
     * @throws com.neo.chat.exception.BadRequestException        on a malformed UUID (TM_852), a
     *                                                              blank body (TM_854), or an invalid parent (TM_856)
     * @throws com.neo.chat.exception.NotFoundException          if the question is missing (TM_853)
     * @throws com.neo.chat.exception.ContentModerationException if the body fails moderation
     */
    @PostMapping("/questions/{uuid}/replies")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<AdviceReplyResponse>> reply(
            @PathVariable("uuid") String uuid,
            @Valid @RequestBody ReplyRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        AdviceReplyResponse response = adviceRoomService.reply(userDetails.getUser(), uuid, request);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reply posted", "TM_000"));
    }

    /**
     * Delete the caller's OWN question (author-only).
     *
     * @throws com.neo.chat.exception.BadRequestException if the UUID is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the question is missing (TM_853)
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not the author (TM_855)
     */
    @DeleteMapping("/questions/{uuid}")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<Void>> deleteQuestion(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        adviceRoomService.deleteMyQuestion(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Question deleted", "TM_000"));
    }

    /**
     * Delete the caller's OWN reply (author-only).
     *
     * @throws com.neo.chat.exception.BadRequestException if the UUID is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the reply is missing (TM_853)
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not the author (TM_855)
     */
    @DeleteMapping("/replies/{uuid}")
    @PreAuthorize("@featureGuard.check('ADVICE_ROOMS')")
    public ResponseEntity<ResponseDto<Void>> deleteReply(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        adviceRoomService.deleteMyReply(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Reply deleted", "TM_000"));
    }
}
