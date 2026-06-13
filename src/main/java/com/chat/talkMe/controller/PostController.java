package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.PostResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class PostController {

    private final PostService postService;

    @PostMapping
    public ResponseEntity<ResponseDto<PostResponse>> createPost(
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.createPost(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Post created successfully", "TM_210"));
    }

    @GetMapping("/feed")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getFeed(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostResponse> response = postService.getFeed(pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/user/{userUuid}")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getProfileFeed(
            @PathVariable("userUuid") String userUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostResponse> response = postService.getProfileFeed(userUuid, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deletePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.deletePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post deleted successfully", "TM_213"));
    }

    @PostMapping("/{id}/like")
    public ResponseEntity<ResponseDto<Void>> likePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.likePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post liked", "TM_214"));
    }

    @DeleteMapping("/{id}/like")
    public ResponseEntity<ResponseDto<Void>> unlikePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.unlikePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post unliked", "TM_215"));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<ResponseDto<PostCommentResponse>> addComment(
            @PathVariable("id") String postUuid,
            @Valid @RequestBody PostCommentRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostCommentResponse response = postService.addComment(postUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Comment added to post", "TM_219"));
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    public ResponseEntity<ResponseDto<Void>> deleteComment(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.deleteComment(postUuid, commentUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Comment deleted from post", "TM_220"));
    }

    @PostMapping("/{id}/bookmark")
    public ResponseEntity<ResponseDto<Void>> bookmarkPost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.bookmarkPost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post bookmarked", "TM_216"));
    }

    @DeleteMapping("/{id}/bookmark")
    public ResponseEntity<ResponseDto<Void>> unbookmarkPost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.unbookmarkPost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post unbookmarked", "TM_217"));
    }
}
