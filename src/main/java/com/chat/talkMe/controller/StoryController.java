package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.dto.response.StoryViewerResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StoryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for 24-hour stories ("status"). All routes require an authenticated
 * {@code ROLE_USER} (class-level {@link PreAuthorize}); the acting user is taken
 * from the security context, never from the request body. Responses are wrapped
 * in the standard {@link ResponseDto} envelope with a {@code TM_xxx} message code.
 */
@RestController
@RequestMapping("/stories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class StoryController {

    private final StoryService storyService;

    /**
     * Posts a new story for the current user, expiring 24 hours after creation.
     *
     * <p>Supports a visual story (image/video) or a VOICE story; a photo paired
     * with music is muxed server-side into an auto-playing video. Followers and
     * following are notified best-effort. See
     * {@link StoryService#createStory(StoryRequest, com.chat.talkMe.domain.User)}
     * for the full rules.
     *
     * @param request     the story payload (media URL, optional caption, audience,
     *                    kind, and optional soundtrack); validated by bean validation
     * @param userDetails the authenticated principal; its user becomes the author
     * @return the created story as it should render for the author
     * @throws com.chat.talkMe.exception.ContentModerationException if the caption is explicit
     * @throws com.chat.talkMe.exception.FeatureLockedException     if a VOICE story is requested
     *                                                              without the VOICE_STATUS entitlement
     * @throws com.chat.talkMe.exception.BadRequestException        if a VOICE story's media is not an
     *                                                              audio clip
     */
    @PostMapping
    public ResponseEntity<ResponseDto<StoryResponse>> createStory(
            @Valid @RequestBody StoryRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StoryResponse response = storyService.createStory(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Story posted successfully", "TM_230"));
    }

    /**
     * Lists the stories the current user is allowed to see right now — every
     * non-expired, non-deleted story, newest first, with FRIENDS-audience stories
     * filtered to accepted followers/following (see the service's visibility rule).
     *
     * @param userDetails the authenticated principal (the viewer)
     * @return the visible active stories, newest first (empty when none)
     */
    @GetMapping("/active")
    public ResponseEntity<ResponseDto<List<StoryResponse>>> getActiveStories(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<StoryResponse> response = storyService.getActiveStories(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Returns the current user's own stories, newest first, <strong>including
     * expired</strong> ones — the "My Stories" archive shown on the profile tab.
     *
     * @param userDetails the authenticated principal whose archive is returned
     * @return the user's stories (active and expired), newest first
     */
    @GetMapping("/mine")
    public ResponseEntity<ResponseDto<List<StoryResponse>>> getMyStories(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<StoryResponse> response = storyService.getMyStories(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Soft-deletes one of the current user's stories (owner-only).
     *
     * @param storyUuid   UUID of the story to delete
     * @param userDetails the authenticated principal; must be the story's author
     * @return an empty success envelope
     * @throws com.chat.talkMe.exception.NotFoundException  if no such story exists
     * @throws com.chat.talkMe.exception.ForbiddenException if the caller is not the story's author
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deleteStory(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        storyService.deleteStory(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Story deleted successfully", "TM_232"));
    }

    /**
     * Records that the current user viewed a story. Idempotent per (story, user);
     * the author viewing their own story is intentionally not counted.
     *
     * @param storyUuid   UUID of the story that was opened
     * @param userDetails the authenticated principal (the viewer)
     * @return an empty success envelope
     * @throws com.chat.talkMe.exception.NotFoundException if no such story exists
     */
    @PostMapping("/{id}/view")
    public ResponseEntity<ResponseDto<Void>> viewStory(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        storyService.viewStory(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Story viewed", "TM_233"));
    }

    /**
     * Returns the "seen by" list for a story — its viewers with per-viewer
     * timestamps, most recent first. Owner-only.
     *
     * @param storyUuid   UUID of the story whose viewers are requested
     * @param userDetails the authenticated principal; must be the story's author
     * @return the viewers, most-recently-viewed first (empty when no one has viewed)
     * @throws com.chat.talkMe.exception.NotFoundException  if no such story exists
     * @throws com.chat.talkMe.exception.ForbiddenException if the caller is not the story's author
     */
    @GetMapping("/{id}/viewers")
    public ResponseEntity<ResponseDto<List<StoryViewerResponse>>> getStoryViewers(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<StoryViewerResponse> response = storyService.getStoryViewers(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
