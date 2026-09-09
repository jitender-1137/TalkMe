package com.neo.chat.controller;

import com.neo.chat.dto.request.ChangeUsernameRequest;
import com.neo.chat.dto.request.DeleteAccountRequest;
import com.neo.chat.dto.request.UpdateProfileRequest;
import com.neo.chat.dto.response.BlockedUserResponse;
import com.neo.chat.dto.response.MutualFriendsResponse;
import com.neo.chat.dto.response.PaginatedResponse;
import com.neo.chat.dto.response.PostResponse;
import com.neo.chat.dto.response.PublicProfileResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SmartProfileCardResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.UserResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.AuthService;
import com.neo.chat.service.FriendService;
import com.neo.chat.service.PostService;
import com.neo.chat.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * User profile, account, social (block/report/mutual-friends), search, and public-profile endpoints.
 * Class-level gate: requires ROLE_USER or ROLE_GUEST, except methods that override it with permitAll
 * (public profile lookup, lobby) or a feature guard (smart profile card).
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER') or hasRole('GUEST')")
@Tag(name = "Users", description = "User profile, account, social (block/report/mutual friends), search and public-profile endpoints")
public class UserController {

    private final UserService userService;
    private final FriendService friendService;
    private final PostService postService;
    private final AuthService authService;

    /**
     * Return the current user's full profile.
     *
     * @param userDetails the authenticated principal
     * @return 200 with the current user's {@link UserResponse}
     */
    @Operation(summary = "Return the current user's full profile")
    @GetMapping("/me")
    public ResponseEntity<ResponseDto<UserResponse>> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse response = userService.getCurrentUser(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Update the current user's editable profile fields (country is immutable once set).
     *
     * @param request     the profile fields to update
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserResponse}
     * @throws com.neo.chat.exception.ContentModerationException if a free-text field fails moderation
     * @throws com.neo.chat.exception.BadRequestException        if the request tries to change the country
     */
    @Operation(summary = "Update the current user's editable profile fields (country is immutable once set)")
    @RequestMapping(value = "/me", method = {RequestMethod.PATCH, RequestMethod.PUT}, consumes = {MediaType.APPLICATION_JSON_VALUE, "application/merge-patch+json"})
    public ResponseEntity<ResponseDto<UserResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        UserResponse response = userService.updateProfile(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Profile updated successfully", "TM_060"));
    }

    /**
     * Change the current user's username (unique; taken names — including accounts
     * pending deletion — are rejected, while fully-purged names are free).
     *
     * @param request     the requested new username
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserResponse}
     * @throws com.neo.chat.exception.BadRequestException if the username is unchanged/invalid
     * @throws com.neo.chat.exception.ConflictException   if the username is already taken
     */
    @Operation(summary = "Change the current user's username (unique; names of accounts pending deletion stay reserved)")
    @PatchMapping(value = "/me/username", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/merge-patch+json"})
    public ResponseEntity<ResponseDto<UserResponse>> changeUsername(
            @Valid @RequestBody ChangeUsernameRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse response = userService.changeUsername(request.getUsername(), userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Username updated", "TM_060"));
    }

    /**
     * Live availability check for the username field.
     *
     * @param username    the candidate username to test
     * @param userDetails the authenticated principal (own current username counts as available)
     * @return 200 with a map {@code {"available": boolean}}
     */
    @Operation(summary = "Live availability check for the username field")
    @GetMapping("/me/username-available")
    public ResponseEntity<ResponseDto<Map<String, Boolean>>> usernameAvailable(
            @RequestParam("username") String username,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        boolean available = userService.isUsernameAvailable(username, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(Map.of("available", available)));
    }

    /**
     * Fast, param-based mood update (feature #4) — e.g. PUT /users/me/mood?value=FLIRT.
     *
     * @param value       the new mood value
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserResponse}
     * @throws com.neo.chat.exception.BadRequestException if the mood value is invalid
     */
    @Operation(summary = "Fast, param-based mood update (feature #4) — e.g. PUT /users/me/mood?value=FLIRT")
    @PutMapping("/me/mood")
    public ResponseEntity<ResponseDto<UserResponse>> updateMood(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse response = userService.updateMood(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Mood updated", "TM_060"));
    }

    /**
     * Upload and set the current user's avatar image.
     *
     * @param file        the avatar image multipart file
     * @param userDetails the authenticated principal
     * @return 200 with a map containing the stored avatar url
     * @throws com.neo.chat.exception.ContentModerationException if the image is explicit
     */
    @Operation(summary = "Upload and set the current user's avatar image")
    @PostMapping(value = "/me/avatar", consumes = "multipart/form-data")
    public ResponseEntity<ResponseDto<Map<String, String>>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Map<String, String> response = userService.uploadAvatar(file, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Avatar uploaded successfully", "TM_USER_001"));
    }

    /**
     * Remove the current user's avatar.
     *
     * @param userDetails the authenticated principal
     * @return 200 with an empty body
     */
    @Operation(summary = "Remove the current user's avatar")
    @DeleteMapping("/me/avatar")
    public ResponseEntity<ResponseDto<Void>> removeAvatar(@AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.removeAvatar(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Avatar removed", "TM_USER_002"));
    }

    /**
     * Soft-delete the current account. It's locked immediately and recoverable for a
     * grace period simply by logging back in; after the window it is permanently
     * anonymized by the scheduled purge job.
     *
     * @param request     optional body carrying the password (required to confirm for password accounts)
     * @param userDetails the authenticated principal
     * @return 200 with an empty body once deletion is scheduled
     * @throws com.neo.chat.exception.ForbiddenException    if the account is a guest (cannot be deleted)
     * @throws com.neo.chat.exception.UnauthorizedException if the supplied password does not match
     */
    @Operation(summary = "Soft-delete the current account")
    @DeleteMapping("/me")
    public ResponseEntity<ResponseDto<Void>> deleteAccount(
            @Valid @RequestBody(required = false) DeleteAccountRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.requestAccountDeletion(userDetails.getUser(),
                request != null ? request.getPassword() : null);
        return ResponseEntity.ok(SuccessResponseDto.success(
                null, "Account scheduled for deletion. Log in again within the recovery window to restore it.",
                "TM_USER_003"));
    }

    /**
     * Fetch another user's profile by UUID (or "me"), enriched with presence, block, and friend flags.
     *
     * @param userId      the target user's UUID, or the literal "me"
     * @param userDetails the authenticated principal
     * @return 200 with the target's {@link UserResponse}
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Fetch another user's profile by UUID (or \"me\"), enriched with presence, block, and friend flags")
    @GetMapping("/{userId}")
    public ResponseEntity<ResponseDto<UserResponse>> getUserById(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        UserResponse response = userService.getUserById(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * PUBLIC profile lookup by username, backing the shareable {@code /@username} link. Anonymous
     * callers are allowed (method-level permitAll overrides the class-level role rule, mirroring
     * {@code /lobby}); it must ALSO be listed in SecurityConfig#unSecured so the filter chain lets
     * it through. Returns a trimmed, PII-free projection.
     *
     * @param username the target's username
     * @return 200 with the {@link PublicProfileResponse} projection
     * @throws com.neo.chat.exception.NotFoundException if no active, public account matches
     */
    @Operation(summary = "PUBLIC profile lookup by username, backing the shareable /@username link")
    @GetMapping("/by-username/{username}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ResponseDto<PublicProfileResponse>> getPublicProfileByUsername(
            @PathVariable("username") String username) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                userService.getPublicProfileByUsername(username)));
    }

    /**
     * Smart Profile Card (feature #20) — late-night attributes + compatibility hint. Gated by the
     * SMART_PROFILE_CARD feature.
     *
     * @param userId      the target user's UUID
     * @param userDetails the authenticated principal (the viewer scored for compatibility)
     * @return 200 with the {@link SmartProfileCardResponse}
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Smart Profile Card (feature #20) — late-night attributes + compatibility hint")
    @GetMapping("/{userId}/card")
    @PreAuthorize("@featureGuard.check('SMART_PROFILE_CARD')")
    public ResponseEntity<ResponseDto<SmartProfileCardResponse>> getSmartProfileCard(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                userService.getSmartProfileCard(userId, userDetails.getUser())));
    }

    /**
     * Cursor-paginated user search by name/username.
     *
     * @param query       the search query (minimum 2 characters)
     * @param limit       page size (default 20)
     * @param cursor      opaque pagination cursor; null for the first page
     * @param userDetails the authenticated principal
     * @return 200 with a {@link PaginatedResponse} of matching users
     * @throws com.neo.chat.exception.BadRequestException if the query is shorter than 2 characters
     */
    @Operation(summary = "Cursor-paginated user search by name/username")
    @GetMapping("/search")
    public ResponseEntity<ResponseDto<PaginatedResponse<UserResponse>>> searchUsers(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PaginatedResponse<UserResponse> response = userService.searchUsers(query, limit, cursor, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Block another user.
     *
     * @param userId      the target user's UUID
     * @param userDetails the authenticated principal
     * @return 200 with an empty body
     * @throws com.neo.chat.exception.NotFoundException   if no user matches the id
     * @throws com.neo.chat.exception.BadRequestException if attempting to block yourself
     */
    @Operation(summary = "Block another user")
    @PostMapping("/{userId}/block")
    public ResponseEntity<ResponseDto<Void>> blockUser(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        friendService.blockUser(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User blocked", "TM_067"));
    }

    /**
     * Unblock a previously blocked user.
     *
     * @param userId      the target user's UUID
     * @param userDetails the authenticated principal
     * @return 200 with an empty body
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Unblock a previously blocked user")
    @DeleteMapping("/{userId}/block")
    public ResponseEntity<ResponseDto<Void>> unblockUser(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        friendService.unblockUser(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User unblocked", "TM_068"));
    }

    /**
     * List the users the current user has blocked.
     *
     * @param userDetails the authenticated principal
     * @return 200 with a {@link PaginatedResponse} of blocked users
     */
    @Operation(summary = "List the users the current user has blocked")
    @GetMapping("/blocked")
    public ResponseEntity<ResponseDto<PaginatedResponse<BlockedUserResponse>>> getBlockedUsers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PaginatedResponse<BlockedUserResponse> response = userService.getBlockedUsers(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * File a moderation report against another user (one open report per reporter→reported pair).
     *
     * @param userId      the reported user's UUID
     * @param payload     body with optional "reason" (default "other") and "description"
     * @param userDetails the authenticated principal (the reporter)
     * @return 200 with an empty body
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     * @throws com.neo.chat.exception.ConflictException if an open report already exists for this pair
     */
    @Operation(summary = "File a moderation report against another user (one open report per reporter→reported pair)")
    @PostMapping(value = "/{userId}/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> reportUser(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        String reason = payload.getOrDefault("reason", "other");
        String description = payload.get("description");

        userService.reportUser(userId, reason, description, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Report submitted", "TM_REPORT_001"));
    }

    /**
     * Paginated profile feed of a user's posts (newest first).
     *
     * @param userId      the target user's UUID
     * @param pageable    pagination/sort (default size 20, createdAt DESC)
     * @param userDetails the authenticated principal (the viewer)
     * @return 200 with a {@link Page} of {@link PostResponse}
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Paginated profile feed of a user's posts (newest first)")
    @GetMapping("/{userId}/posts")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getUserPosts(
            @PathVariable("userId") String userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Page<PostResponse> response = postService.getProfileFeed(userId, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Fetch a user's profile by UUID (alias of {@link #getUserById} for the /profile route).
     *
     * @param userId      the target user's UUID
     * @param userDetails the authenticated principal
     * @return 200 with the target's {@link UserResponse}
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Fetch a user's profile by UUID (alias of getUserById for the /profile route)")
    @GetMapping("/{userId}/profile")
    public ResponseEntity<ResponseDto<UserResponse>> getUserProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        UserResponse response = userService.getUserById(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Mutual friends between the current user and the target user.
     *
     * @param userId      the target user's UUID
     * @param userDetails the authenticated principal
     * @return 200 with a {@link MutualFriendsResponse} (count + sample)
     * @throws com.neo.chat.exception.NotFoundException if no user matches the id
     */
    @Operation(summary = "Mutual friends between the current user and the target user")
    @GetMapping("/{userId}/mutual-friends")
    public ResponseEntity<ResponseDto<MutualFriendsResponse>> getMutualFriends(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        MutualFriendsResponse response = userService.getMutualFriends(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * List currently-visible lobby users. Public (permitAll); the principal may be null for
     * anonymous callers.
     *
     * @param userDetails the authenticated principal, or null when anonymous
     * @return 200 with the list of lobby {@link UserResponse}
     */
    @Operation(summary = "List currently-visible lobby users")
    @GetMapping("/lobby")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ResponseDto<List<UserResponse>>> getLobbyUsers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<UserResponse> response = userService.getLobbyUsers(userDetails != null ? userDetails.getUser() : null);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
