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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages the follow graph (follow, unfollow, remove-follower, and paginated follower/following
 * lists). Served at {@code /follows}; every route is gated by {@code hasRole('USER')} at class level.
 */
@RestController
@RequestMapping("/follows")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class FollowController {

    private final FollowService followService;

    /**
     * Makes the current user follow the target user and notifies the target of the new follower.
     *
     * @param userUuid    the UUID of the user to follow
     * @param userDetails the authenticated user performing the follow
     * @return 200 with an empty payload and success code TM_254
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if following self or already following
     */
    @PostMapping("/{userUuid}")
    public ResponseEntity<ResponseDto<Void>> followUser(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.followUser(userUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully followed user", "TM_254"));
    }

    /**
     * Makes the current user stop following the target user (soft-deletes the follow edge).
     *
     * @param userUuid    the UUID of the user to unfollow
     * @param userDetails the authenticated user performing the unfollow
     * @return 200 with an empty payload and success code TM_255
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if not currently following the user
     */
    @DeleteMapping("/{userUuid}")
    public ResponseEntity<ResponseDto<Void>> unfollowUser(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.unfollowUser(userUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully unfollowed user", "TM_255"));
    }

    /**
     * Removes one of the current user's followers (soft-deletes their follow edge to the caller).
     *
     * @param followerUuid the UUID of the follower to remove
     * @param userDetails  the authenticated user being followed
     * @return 200 with an empty payload and success code TM_256
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if that user is not following the caller
     */
    @DeleteMapping("/followers/{followerUuid}")
    public ResponseEntity<ResponseDto<Void>> removeFollower(
            @PathVariable("followerUuid") String followerUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        followService.removeFollower(followerUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Successfully removed follower", "TM_256"));
    }

    /**
     * Returns a paginated list of the target user's accepted followers ({@code "me"} resolves to
     * the current user).
     *
     * @param userUuid    the UUID of the user whose followers to list, or "me" for the caller
     * @param userDetails the authenticated caller (used to resolve "me")
     * @param pageable    pagination (defaults to size 20, sorted by createdAt descending)
     * @return 200 with a page of follower user summaries
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the resolved UUID
     */
    @GetMapping("/{userUuid}/followers")
    public ResponseEntity<ResponseDto<Page<AuthUserResponse>>> getFollowers(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String resolvedUuid = "me".equalsIgnoreCase(userUuid) ? userDetails.getUser().getUuid().toString() : userUuid;
        Page<AuthUserResponse> followers = followService.getFollowers(resolvedUuid, pageable);
        return ResponseEntity.ok(SuccessResponseDto.success(followers));
    }

    /**
     * Returns a paginated list of the users the target user follows ({@code "me"} resolves to the
     * current user).
     *
     * @param userUuid    the UUID of the user whose following list to fetch, or "me" for the caller
     * @param userDetails the authenticated caller (used to resolve "me")
     * @param pageable    pagination (defaults to size 20, sorted by createdAt descending)
     * @return 200 with a page of followed user summaries
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the resolved UUID
     */
    @GetMapping("/{userUuid}/following")
    public ResponseEntity<ResponseDto<Page<AuthUserResponse>>> getFollowing(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String resolvedUuid = "me".equalsIgnoreCase(userUuid) ? userDetails.getUser().getUuid().toString() : userUuid;
        Page<AuthUserResponse> following = followService.getFollowing(resolvedUuid, pageable);
        return ResponseEntity.ok(SuccessResponseDto.success(following));
    }
}
