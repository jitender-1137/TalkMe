package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/stories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class StoryController {

    private final StoryService storyService;

    @PostMapping
    public ResponseEntity<ResponseDto<StoryResponse>> createStory(
            @Valid @RequestBody StoryRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StoryResponse response = storyService.createStory(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Story posted successfully", "TM_230"));
    }

    @GetMapping("/active")
    public ResponseEntity<ResponseDto<List<StoryResponse>>> getActiveStories(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<StoryResponse> response = storyService.getActiveStories(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deleteStory(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        storyService.deleteStory(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Story deleted successfully", "TM_232"));
    }

    @PostMapping("/{id}/view")
    public ResponseEntity<ResponseDto<Void>> viewStory(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        storyService.viewStory(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Story viewed", "TM_233"));
    }

    @GetMapping("/{id}/viewers")
    public ResponseEntity<ResponseDto<List<AuthUserResponse>>> getStoryViewers(
            @PathVariable("id") String storyUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<AuthUserResponse> response = storyService.getStoryViewers(storyUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
