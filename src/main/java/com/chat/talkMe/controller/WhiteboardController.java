package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.WhiteboardStrokeRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.WhiteboardOp;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WhiteboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Shared Whiteboard surface (feature SHARED_WHITEBOARD). Real-time collaborative drawing inside a
 * 1:1 chat. Strokes are ephemeral — the server keeps a capped Redis op-log per chat and, on every
 * mutation, re-broadcasts the op on the existing chat topic {@code /topic/chat/{chatUuid}/messages}.
 *
 * <p>Every route is gated by the SHARED_WHITEBOARD feature and membership-checked (IDOR) inside the
 * service.
 */
@RestController
@RequestMapping("/whiteboard")
@RequiredArgsConstructor
public class WhiteboardController {

    private final WhiteboardService whiteboardService;

    /**
     * Return the current op-log for a chat's shared whiteboard. Feature-gated + membership-checked.
     *
     * @param chatUuid    the chat's UUID
     * @param userDetails the authenticated principal
     * @return 200 with the list of {@link WhiteboardOp}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @GetMapping("/{chatUuid}")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<List<WhiteboardOp>>> getBoard(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<WhiteboardOp> ops = whiteboardService.getBoard(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(ops));
    }

    /**
     * Append a stroke to the whiteboard and re-broadcast it on the chat topic. Feature-gated +
     * membership-checked.
     *
     * @param request     the stroke (points, style) to add
     * @param userDetails the authenticated principal
     * @return 200 with the created {@link WhiteboardOp}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is invalid or the stroke
     *                                                       exceeds the point cap / has a malformed point
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @PostMapping("/stroke")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<WhiteboardOp>> addStroke(
            @Valid @RequestBody WhiteboardStrokeRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WhiteboardOp op = whiteboardService.addStroke(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(op, "Stroke added", "TM_822"));
    }

    /**
     * Clear the whole whiteboard and broadcast the clear op. Feature-gated + membership-checked.
     *
     * @param chatUuid    the chat's UUID
     * @param userDetails the authenticated principal
     * @return 200 with an empty body
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @PostMapping("/{chatUuid}/clear")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<Void>> clear(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        whiteboardService.clear(userDetails.getUser(), chatUuid);
        ResponseDto<Void> body = ResponseDto.success(null, "Whiteboard cleared", "TM_823");
        return ResponseEntity.ok(body);
    }

    /**
     * Undo the last op and broadcast the undo. Feature-gated + membership-checked.
     *
     * @param chatUuid    the chat's UUID
     * @param userDetails the authenticated principal
     * @return 200 with the resulting {@link WhiteboardOp}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @PostMapping("/{chatUuid}/undo")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<WhiteboardOp>> undo(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WhiteboardOp op = whiteboardService.undo(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(op, "Undo broadcast", "TM_824"));
    }
}
