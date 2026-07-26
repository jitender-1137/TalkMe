package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.DeleteAccountRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.PostResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.service.FriendService;
import com.chat.talkMe.service.PostService;
import com.chat.talkMe.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER') or hasRole('GUEST')")
public class UserController {

    private final UserService userService;
    private final FriendService friendService;
    private final PostService postService;
    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<ResponseDto<UserResponse>> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse response = userService.getCurrentUser(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @RequestMapping(value = "/me", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public ResponseEntity<ResponseDto<UserResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        UserResponse response = userService.updateProfile(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Profile updated successfully", "TM_060"));
    }

    /** Fast, param-based mood update (feature #4) — e.g. PUT /users/me/mood?value=FLIRT. */
    @PutMapping("/me/mood")
    public ResponseEntity<ResponseDto<UserResponse>> updateMood(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse response = userService.updateMood(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Mood updated", "TM_060"));
    }

    @PostMapping(value = "/me/avatar", consumes = "multipart/form-data")
    public ResponseEntity<ResponseDto<Map<String, String>>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        Map<String, String> response = userService.uploadAvatar(file, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Avatar uploaded successfully", "TM_USER_001"));
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<ResponseDto<Void>> removeAvatar(@AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.removeAvatar(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Avatar removed", "TM_USER_002"));
    }

    /**
     * Soft-delete the current account. It's locked immediately and recoverable for a
     * grace period simply by logging back in; after the window it is permanently
     * anonymized by the scheduled purge job.
     */
    @DeleteMapping("/me")
    public ResponseEntity<ResponseDto<Void>> deleteAccount(
            @RequestBody(required = false) DeleteAccountRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.requestAccountDeletion(userDetails.getUser(),
                request != null ? request.getPassword() : null);
        return ResponseEntity.ok(SuccessResponseDto.success(
                null, "Account scheduled for deletion. Log in again within the recovery window to restore it.",
                "TM_USER_003"));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ResponseDto<UserResponse>> getUserById(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        UserResponse response = userService.getUserById(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** Smart Profile Card (feature #20) — late-night attributes + compatibility hint. */
    @GetMapping("/{userId}/card")
    @PreAuthorize("@featureGuard.check('SMART_PROFILE_CARD')")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.SmartProfileCardResponse>> getSmartProfileCard(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                userService.getSmartProfileCard(userId, userDetails.getUser())));
    }

    @GetMapping("/search")
    public ResponseEntity<ResponseDto<PaginatedResponse<UserResponse>>> searchUsers(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PaginatedResponse<UserResponse> response = userService.searchUsers(query, limit, cursor, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/{userId}/block")
    public ResponseEntity<ResponseDto<Void>> blockUser(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        friendService.blockUser(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User blocked", "TM_067"));
    }

    @DeleteMapping("/{userId}/block")
    public ResponseEntity<ResponseDto<Void>> unblockUser(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        friendService.unblockUser(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User unblocked", "TM_068"));
    }

    @GetMapping("/blocked")
    public ResponseEntity<ResponseDto<PaginatedResponse<BlockedUserResponse>>> getBlockedUsers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        PaginatedResponse<BlockedUserResponse> response = userService.getBlockedUsers(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/{userId}/report")
    public ResponseEntity<ResponseDto<Void>> reportUser(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        String reason = payload.getOrDefault("reason", "other");
        String description = payload.get("description");

        userService.reportUser(userId, reason, description, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Report submitted", "TM_REPORT_001"));
    }

    @GetMapping("/{userId}/posts")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getUserPosts(
            @PathVariable("userId") String userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        Page<PostResponse> response = postService.getProfileFeed(userId, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<ResponseDto<UserResponse>> getUserProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        UserResponse response = userService.getUserById(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/{userId}/mutual-friends")
    public ResponseEntity<ResponseDto<MutualFriendsResponse>> getMutualFriends(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        MutualFriendsResponse response = userService.getMutualFriends(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/lobby")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ResponseDto<java.util.List<UserResponse>>> getLobbyUsers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        java.util.List<UserResponse> response = userService.getLobbyUsers(userDetails != null ? userDetails.getUser() : null);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
