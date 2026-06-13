package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/follows")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FollowController {

    private final FollowService followService;

    @PostMapping("/{userUuid}")
    public ResponseEntity<ResponseDto<Void>> followUser(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.followUser(userUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully followed user", "TM_254"));
    }

    @DeleteMapping("/{userUuid}")
    public ResponseEntity<ResponseDto<Void>> unfollowUser(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.unfollowUser(userUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully unfollowed user", "TM_255"));
    }

    @DeleteMapping("/followers/{followerUuid}")
    public ResponseEntity<ResponseDto<Void>> removeFollower(
            @PathVariable("followerUuid") String followerUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.removeFollower(followerUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully removed follower", "TM_256"));
    }

    @GetMapping("/{userUuid}/followers")
    public ResponseEntity<ResponseDto<Page<AuthUserResponse>>> getFollowers(
            @PathVariable("userUuid") String userUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AuthUserResponse> followers = followService.getFollowers(userUuid, pageable);
        return ResponseEntity.ok(SuccessResponseDto.success(followers));
    }

    @GetMapping("/{userUuid}/following")
    public ResponseEntity<ResponseDto<Page<AuthUserResponse>>> getFollowing(
            @PathVariable("userUuid") String userUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AuthUserResponse> following = followService.getFollowing(userUuid, pageable);
        return ResponseEntity.ok(SuccessResponseDto.success(following));
    }
}
