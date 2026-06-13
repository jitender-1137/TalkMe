package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FriendController {

    private final FriendService friendService;

    @PostMapping("/requests")
    public ResponseEntity<ResponseDto<FriendRequestResponse>> sendFriendRequest(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String receiverId = payload.get("receiverId");
        FriendRequestResponse response = friendService.sendFriendRequest(receiverId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Friend request sent successfully", "TM_090"));
    }

    @PutMapping("/requests/{id}/accept")
    public ResponseEntity<ResponseDto<Void>> acceptFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.acceptFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request accepted", "TM_091"));
    }

    @PutMapping("/requests/{id}/decline")
    public ResponseEntity<ResponseDto<Void>> rejectFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.rejectFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request rejected", "TM_092"));
    }

    @DeleteMapping("/requests/{id}/cancel")
    public ResponseEntity<ResponseDto<Void>> cancelFriendRequest(
            @PathVariable("id") String requestUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.cancelFriendRequest(requestUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend request canceled", "TM_093"));
    }

    @GetMapping
    public ResponseEntity<ResponseDto<List<AuthUserResponse>>> getFriends(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<AuthUserResponse> response = friendService.getFriends(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/requests")
    public ResponseEntity<ResponseDto<List<FriendRequestResponse>>> getFriendRequests(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<FriendRequestResponse> response = friendService.getFriendRequests(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> removeFriend(
            @PathVariable("id") String friendUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.removeFriend(friendUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Friend removed successfully", "TM_098"));
    }

    @PostMapping("/block/{id}")
    public ResponseEntity<ResponseDto<Void>> blockUser(
            @PathVariable("id") String targetUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.blockUser(targetUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User blocked successfully", "TM_067"));
    }

    @DeleteMapping("/block/{id}")
    public ResponseEntity<ResponseDto<Void>> unblockUser(
            @PathVariable("id") String targetUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        friendService.unblockUser(targetUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User unblocked successfully", "TM_068"));
    }
}
