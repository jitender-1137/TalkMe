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
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{chatUuid}")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<List<WhiteboardOp>>> getBoard(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<WhiteboardOp> ops = whiteboardService.getBoard(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(ops));
    }

    @PostMapping("/stroke")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<WhiteboardOp>> addStroke(
            @Valid @RequestBody WhiteboardStrokeRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WhiteboardOp op = whiteboardService.addStroke(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(op, "Stroke added", "TM_822"));
    }

    @PostMapping("/{chatUuid}/clear")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<Void>> clear(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        whiteboardService.clear(userDetails.getUser(), chatUuid);
        ResponseDto<Void> body = ResponseDto.success(null, "Whiteboard cleared", "TM_823");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{chatUuid}/undo")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<WhiteboardOp>> undo(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WhiteboardOp op = whiteboardService.undo(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(op, "Undo broadcast", "TM_824"));
    }
}
