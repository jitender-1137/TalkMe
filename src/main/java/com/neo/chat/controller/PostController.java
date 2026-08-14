package com.neo.chat.controller;

import com.neo.chat.dto.request.PollVoteRequest;
import com.neo.chat.dto.request.PostCommentRequest;
import com.neo.chat.dto.request.PostRequest;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.PostCommentResponse;
import com.neo.chat.dto.response.PostResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.PostService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Social feed posts and their poll/like/comment/bookmark interactions. Every route requires ROLE_USER.
 */
@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class PostController {

    private final PostService postService;

    /**
     * Creates a new feed post (text, media, and/or poll) for the current user.
     *
     * @param request     the post payload; must carry text, media, or a poll
     * @param userDetails authenticated author
     * @return the created {@link PostResponse}
     * @throws com.neo.chat.exception.BadRequestException        if the post is empty or a poll has fewer than 2
     *                                                              options
     * @throws com.neo.chat.exception.ContentModerationException if text or media violates community guidelines
     * @throws com.neo.chat.exception.FeatureLockedException     if a used capability is not entitled for the user
     */
    @PostMapping
    public ResponseEntity<ResponseDto<PostResponse>> createPost(
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.createPost(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Post created successfully", "TM_210"));
    }

    /**
     * Casts (or, when re-tapping the current choice, retracts) the caller's vote on a post's poll.
     *
     * @param postUuid    UUID of the post carrying the poll
     * @param request     body carrying the chosen poll option id
     * @param userDetails authenticated voter
     * @return the updated {@link PostResponse} with refreshed poll tallies
     * @throws com.neo.chat.exception.NotFoundException   if the post or poll option does not exist
     * @throws com.neo.chat.exception.BadRequestException if the post is not a poll or the option is not on this poll
     */
    @PostMapping("/{id}/poll/vote")
    public ResponseEntity<ResponseDto<PostResponse>> votePoll(
            @PathVariable("id") String postUuid,
            @Valid @RequestBody PollVoteRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.votePoll(postUuid, request.getOptionId(), userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Vote recorded", "TM_229"));
    }

    /**
     * Fetches a single post by its UUID, rendered from the caller's viewpoint.
     *
     * @param postUuid    UUID of the post
     * @param userDetails authenticated caller
     * @return the {@link PostResponse}
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist or is not visible
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseDto<PostResponse>> getPost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.getPost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Fetches a single post by its share short-code, rendered from the caller's viewpoint.
     *
     * @param shortCode   the post's public short code
     * @param userDetails authenticated caller
     * @return the {@link PostResponse}
     * @throws com.neo.chat.exception.NotFoundException if no post matches the short code
     */
    @GetMapping("/by-code/{code}")
    public ResponseEntity<ResponseDto<PostResponse>> getPostByShortCode(
            @PathVariable("code") String shortCode,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.getPostByShortCode(shortCode, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Updates a post owned by the caller.
     *
     * @param postUuid    UUID of the post to update
     * @param request     the new post payload
     * @param userDetails authenticated caller (must be the author)
     * @return the updated {@link PostResponse}
     * @throws com.neo.chat.exception.NotFoundException          if the post does not exist
     * @throws com.neo.chat.exception.ForbiddenException         if the caller is not the post's author
     * @throws com.neo.chat.exception.ContentModerationException if the edited content violates guidelines
     */
    @PutMapping("/{id}")
    public ResponseEntity<ResponseDto<PostResponse>> updatePost(
            @PathVariable("id") String postUuid,
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostResponse response = postService.updatePost(postUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Post updated successfully", "TM_214"));
    }

    /**
     * Returns the caller's home feed of posts, newest first.
     *
     * @param pageable    pagination (default size 20, sorted by createdAt DESC)
     * @param userDetails authenticated caller
     * @return a page of {@link PostResponse}
     */
    @GetMapping("/feed")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getFeed(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostResponse> response = postService.getFeed(pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Returns a given user's profile feed of posts, newest first.
     *
     * @param userUuid    UUID of the profile owner whose posts are listed
     * @param pageable    pagination (default size 20, sorted by createdAt DESC)
     * @param userDetails authenticated caller
     * @return a page of {@link PostResponse}
     * @throws com.neo.chat.exception.NotFoundException if the profile owner does not exist
     */
    @GetMapping("/user/{userUuid}")
    public ResponseEntity<ResponseDto<Page<PostResponse>>> getProfileFeed(
            @PathVariable("userUuid") String userUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostResponse> response = postService.getProfileFeed(userUuid, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Deletes a post owned by the caller.
     *
     * @param postUuid    UUID of the post to delete
     * @param userDetails authenticated caller (must be the author)
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException  if the post does not exist
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the post's author
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDto<Void>> deletePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.deletePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post deleted successfully", "TM_213"));
    }

    /**
     * Adds the caller's like to a post (idempotent).
     *
     * @param postUuid    UUID of the post to like
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @PostMapping("/{id}/like")
    public ResponseEntity<ResponseDto<Void>> likePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.likePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post liked", "TM_214"));
    }

    /**
     * Removes the caller's like from a post (idempotent).
     *
     * @param postUuid    UUID of the post to unlike
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @DeleteMapping("/{id}/like")
    public ResponseEntity<ResponseDto<Void>> unlikePost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.unlikePost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post unliked", "TM_215"));
    }

    /**
     * Lists the users who liked a post ("Liked by"), newest first.
     *
     * @param postUuid    UUID of the post
     * @param pageable    pagination (default size 30, sorted by createdAt DESC)
     * @param userDetails authenticated caller
     * @return a page of {@link AuthUserResponse} likes
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @GetMapping("/{id}/likes")
    public ResponseEntity<ResponseDto<Page<AuthUserResponse>>> getPostLikes(
            @PathVariable("id") String postUuid,
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<AuthUserResponse> response = postService.getPostLikes(postUuid, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Adds a comment (or reply, when the request carries a parent id) to a post.
     *
     * @param postUuid    UUID of the post being commented on
     * @param request     the comment content and optional parent comment id
     * @param userDetails authenticated commenter
     * @return the created {@link PostCommentResponse}
     * @throws com.neo.chat.exception.ContentModerationException if the comment violates community guidelines
     * @throws com.neo.chat.exception.NotFoundException          if the post does not exist
     */
    @PostMapping("/{id}/comments")
    public ResponseEntity<ResponseDto<PostCommentResponse>> addComment(
            @PathVariable("id") String postUuid,
            @Valid @RequestBody PostCommentRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostCommentResponse response = postService.addComment(postUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Comment added to post", "TM_219"));
    }

    /**
     * Lists top-level comments on a post, newest first.
     *
     * @param postUuid    UUID of the post
     * @param pageable    pagination (default size 15, sorted by createdAt DESC)
     * @param userDetails authenticated caller
     * @return a page of {@link PostCommentResponse}
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<ResponseDto<Page<PostCommentResponse>>> getComments(
            @PathVariable("id") String postUuid,
            @PageableDefault(size = 15, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostCommentResponse> response = postService.getComments(postUuid, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Lists the replies to a given comment, oldest first.
     *
     * @param postUuid    UUID of the post
     * @param commentUuid UUID of the parent comment
     * @param pageable    pagination (default size 10, sorted by createdAt ASC)
     * @param userDetails authenticated caller
     * @return a page of reply {@link PostCommentResponse}
     * @throws com.neo.chat.exception.NotFoundException if the parent comment does not exist
     */
    @GetMapping("/{id}/comments/{commentId}/replies")
    public ResponseEntity<ResponseDto<Page<PostCommentResponse>>> getReplies(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<PostCommentResponse> response = postService.getReplies(postUuid, commentUuid, pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Edits a comment owned by the caller.
     *
     * @param postUuid    UUID of the post
     * @param commentUuid UUID of the comment to edit
     * @param request     the new comment content
     * @param userDetails authenticated caller (must be the comment's author)
     * @return the updated {@link PostCommentResponse}
     * @throws com.neo.chat.exception.NotFoundException  if the comment does not exist
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the comment's author
     */
    @PutMapping("/{id}/comments/{commentId}")
    public ResponseEntity<ResponseDto<PostCommentResponse>> editComment(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @Valid @RequestBody PostCommentRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        PostCommentResponse response = postService.editComment(postUuid, commentUuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Comment updated", "TM_222"));
    }

    /**
     * Deletes a comment owned by the caller.
     *
     * @param postUuid    UUID of the post
     * @param commentUuid UUID of the comment to delete
     * @param userDetails authenticated caller (must be the comment's author)
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException  if the comment does not exist
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the comment's author
     */
    @DeleteMapping("/{id}/comments/{commentId}")
    public ResponseEntity<ResponseDto<Void>> deleteComment(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.deleteComment(postUuid, commentUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Comment deleted from post", "TM_220"));
    }

    /**
     * Adds the caller's like to a comment (idempotent).
     *
     * @param postUuid    UUID of the post
     * @param commentUuid UUID of the comment to like
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the comment does not exist
     */
    @PostMapping("/{id}/comments/{commentId}/like")
    public ResponseEntity<ResponseDto<Void>> likeComment(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.likeComment(postUuid, commentUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Comment liked", "TM_223"));
    }

    /**
     * Removes the caller's like from a comment (idempotent).
     *
     * @param postUuid    UUID of the post
     * @param commentUuid UUID of the comment to unlike
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the comment does not exist
     */
    @DeleteMapping("/{id}/comments/{commentId}/like")
    public ResponseEntity<ResponseDto<Void>> unlikeComment(
            @PathVariable("id") String postUuid,
            @PathVariable("commentId") String commentUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.unlikeComment(postUuid, commentUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Comment unliked", "TM_224"));
    }

    /**
     * Bookmarks a post for the caller (idempotent).
     *
     * @param postUuid    UUID of the post to bookmark
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @PostMapping("/{id}/bookmark")
    public ResponseEntity<ResponseDto<Void>> bookmarkPost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.bookmarkPost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post bookmarked", "TM_216"));
    }

    /**
     * Removes the caller's bookmark from a post (idempotent).
     *
     * @param postUuid    UUID of the post to unbookmark
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if the post does not exist
     */
    @DeleteMapping("/{id}/bookmark")
    public ResponseEntity<ResponseDto<Void>> unbookmarkPost(
            @PathVariable("id") String postUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        postService.unbookmarkPost(postUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Post unbookmarked", "TM_217"));
    }
}
