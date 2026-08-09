package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Friend graph management: friend requests (send/accept/decline/cancel), the friend list, and
 * user block/unblock. Every route is gated by {@code hasRole('USER')}.
 */
@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FriendController {

    private final FriendService friendService;

    /**
     * Send (or re-send) a friend request; auto-accepts if the receiver already sent one to you.
     *
     * @param payload     body carrying {@code receiverId} (the target user's UUID)
     * @param userDetails the authenticated sender
     * @return the created/updated friend request wrapped in a success envelope (TM_090)
     * @throws com.chat.talkMe.exception.NotFoundException        receiver UUID does not exist (TM_064)
     * @throws com.chat.talkMe.exception.BadRequestException      sending a request to yourself (TM_097)
     * @throws com.chat.talkMe.exception.ForbiddenException       either party has blocked the other (TM_103)
     * @throws com.chat.talkMe.exception.ConflictException        already friends with this user (TM_096)
     * @throws com.chat.talkMe.exception.TooManyRequestsException daily new-request cap reached (TM_498)
     */
    @PostMapping("/requests")
    public ResponseEntity<ResponseDto<FriendRequestResponse>> sendFriendRequest(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String receiverId = payload.get("receiverId");
        FriendRequestResponse response = friendService.sendFriendRequest(receiverId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Friend request sent successfully", "TM_090"));
    }

    /**
     * Accept a pending friend request (creates the mutual friendship).
     *
     * @param requestUuid UUID of the friend request to accept
     * @param userDetails the authenticated receiver of the request
     * @return empty success envelope (TM_091)
     * @throws com.chat.talkMe.exception.NotFoundException  request UUID does not exist (TM_094)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not the request's receiver (TM_103)
     * @throws com.chat.talkMe.exception.ConflictException  request is not PENDING (already processed) (TM_096)
     */
    @PutMapping("/requests/{id}/accept")
    public ResponseEntity<ResponseDto<Void>> acceptFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.acceptFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request accepted", "TM_091"));
    }

    /**
     * Decline a pending friend request.
     *
     * @param requestUuid UUID of the friend request to decline
     * @param userDetails the authenticated receiver of the request
     * @return empty success envelope (TM_092)
     * @throws com.chat.talkMe.exception.NotFoundException  request UUID does not exist (TM_094)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not the request's receiver (TM_103)
     * @throws com.chat.talkMe.exception.ConflictException  request is not PENDING (already processed) (TM_096)
     */
    @PutMapping("/requests/{id}/decline")
    public ResponseEntity<ResponseDto<Void>> rejectFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.rejectFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request rejected", "TM_092"));
    }

    /**
     * Cancel a friend request you sent (deletes it).
     *
     * @param requestUuid UUID of the friend request to cancel
     * @param userDetails the authenticated sender of the request
     * @return empty success envelope (TM_093)
     * @throws com.chat.talkMe.exception.NotFoundException  request UUID does not exist (TM_094)
     * @throws com.chat.talkMe.exception.ForbiddenException caller is not the request's sender (TM_103)
     */
    @DeleteMapping("/requests/{id}/cancel")
    public ResponseEntity<ResponseDto<Void>> cancelFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.cancelFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request canceled", "TM_093"));
    }

    /**
     * List the caller's friends, each enriched with presence and apparent last-seen.
     *
     * @param userDetails the authenticated user
     * @return the caller's friend list wrapped in a success envelope
     */
    @GetMapping
    public ResponseEntity<ResponseDto<List<AuthUserResponse>>> getFriends(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<AuthUserResponse> response = friendService.getFriends(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * List the caller's incoming PENDING friend requests (newest first).
     *
     * @param userDetails the authenticated receiver
     * @return the pending inbound requests wrapped in a success envelope
     */
    @GetMapping("/requests")
    public ResponseEntity<ResponseDto<List<FriendRequestResponse>>> getFriendRequests(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<FriendRequestResponse> response = friendService.getFriendRequests(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Remove a friend (drops the mutual friendship and clears any prior requests between them).
     *
     * @param friendUuid  UUID of the friend to remove
     * @param userDetails the authenticated user
     * @return empty success envelope (TM_098)
     * @throws com.chat.talkMe.exception.NotFoundException friend UUID does not exist (TM_064)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> removeFriend(
            @PathVariable("id") String friendUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.removeFriend(friendUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend removed successfully", "TM_098"));
    }

    /**
     * Block a user (also removes any existing friendship); idempotent if already blocked.
     *
     * @param targetUuid  UUID of the user to block
     * @param userDetails the authenticated user
     * @return empty success envelope (TM_067)
     * @throws com.chat.talkMe.exception.NotFoundException   target UUID does not exist (TM_064)
     * @throws com.chat.talkMe.exception.BadRequestException blocking yourself (TM_071)
     */
    @PostMapping("/block/{id}")
    public ResponseEntity<ResponseDto<Void>> blockUser(
            @PathVariable("id") String targetUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.blockUser(targetUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User blocked successfully", "TM_067"));
    }

    /**
     * Unblock a previously blocked user; a no-op if they were not blocked.
     *
     * @param targetUuid  UUID of the user to unblock
     * @param userDetails the authenticated user
     * @return empty success envelope (TM_068)
     * @throws com.chat.talkMe.exception.NotFoundException target UUID does not exist (TM_064)
     */
    @DeleteMapping("/block/{id}")
    public ResponseEntity<ResponseDto<Void>> unblockUser(
            @PathVariable("id") String targetUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.unblockUser(targetUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User unblocked successfully", "TM_068"));
    }
}
