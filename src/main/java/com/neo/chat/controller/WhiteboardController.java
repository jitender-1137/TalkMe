package com.neo.chat.controller;

import com.neo.chat.dto.request.WhiteboardStrokeRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.WhiteboardOp;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.WhiteboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
@Tag(name = "Whiteboard", description = "Shared real-time collaborative whiteboard inside a 1:1 chat")
public class WhiteboardController {

    private final WhiteboardService whiteboardService;

    /**
     * Return the current op-log for a chat's shared whiteboard. Feature-gated + membership-checked.
     *
     * @param chatUuid    the chat's UUID
     * @param userDetails the authenticated principal
     * @return 200 with the list of {@link WhiteboardOp}
     * @throws com.neo.chat.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @Operation(summary = "Return the current op-log for a chat's shared whiteboard")
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
     * @throws com.neo.chat.exception.BadRequestException if the chat id is invalid or the stroke
     *                                                       exceeds the point cap / has a malformed point
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @Operation(summary = "Append a stroke to the whiteboard and re-broadcast it on the chat topic")
    @PostMapping(value = "/stroke", consumes = MediaType.APPLICATION_JSON_VALUE)
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
     * @throws com.neo.chat.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @Operation(summary = "Clear the whole whiteboard and broadcast the clear op")
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
     * @throws com.neo.chat.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @Operation(summary = "Undo the last op and broadcast the undo")
    @PostMapping("/{chatUuid}/undo")
    @PreAuthorize("@featureGuard.check('SHARED_WHITEBOARD')")
    public ResponseEntity<ResponseDto<WhiteboardOp>> undo(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WhiteboardOp op = whiteboardService.undo(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(op, "Undo broadcast", "TM_824"));
    }
}
