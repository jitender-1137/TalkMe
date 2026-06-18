package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.PostMediaResponse;
import com.chat.talkMe.dto.response.PostResponse;
import com.chat.talkMe.exception.*;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.PostService;
import com.chat.talkMe.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public PostResponse createPost(PostRequest request, User currentUser) {
        Post post = Post.builder()
                .user(currentUser)
                .content(request.getContent())
                .build();

        post = postRepository.save(post);

        if (request.getMedia() != null) {
            int order = 0;
            for (var mediaReq : request.getMedia()) {
                PostMedia media = PostMedia.builder()
                        .post(post)
                        .mediaUrl(mediaReq.getMediaUrl())
                        .mediaType(mediaReq.getMediaType())
                        .orderIndex(order++)
                        .build();
                postMediaRepository.save(media);
                post.getMedia().add(media);
            }
        }

        log.info("Post created successfully by {}", currentUser.getUsername());
        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional
    public PostResponse updatePost(String postUuid, PostRequest request, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        if (!post.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot edit post of another user", "TM_103");
        }

        post.setContent(request.getContent());
        post = postRepository.save(post);
        log.info("Post {} caption updated by {}", postUuid, currentUser.getUsername());
        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostResponse> getFeed(Pageable pageable, User currentUser) {
        Page<Post> posts = postRepository.findByIsDeletedFalse(pageable);
        return posts.map(post -> mapToPostResponse(post, currentUser));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostResponse> getProfileFeed(String userUuid, Pageable pageable, User currentUser) {
        User targetUser;
        if ("me".equalsIgnoreCase(userUuid)) {
            targetUser = currentUser;
        } else {
            targetUser = userRepository.findByUuid(UUID.fromString(userUuid))
                    .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        }

        Page<Post> posts = postRepository.findByUserAndIsDeletedFalse(targetUser, pageable);
        return posts.map(post -> mapToPostResponse(post, currentUser));
    }

    @Override
    @Transactional
    public void deletePost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        if (!post.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot delete post of another user", "TM_103");
        }

        post.setDeleted(true);
        postRepository.save(post);
    }

    @Override
    @Transactional
    public void likePost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        if (postLikeRepository.existsByPostAndUser(post, currentUser)) {
            return; // already liked
        }

        PostLike like = PostLike.builder().post(post).user(currentUser).build();
        postLikeRepository.save(like);
        
        if (!post.getUser().getId().equals(currentUser.getId())) {
            notificationService.createNotification(
                post.getUser(),
                "New Like",
                currentUser.getUsername() + " liked your post.",
                "LIKE",
                post.getUuid().toString()
            );
        }
    }

    @Override
    @Transactional
    public void unlikePost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        postLikeRepository.findByPostAndUser(post, currentUser)
                .ifPresent(postLikeRepository::delete);
    }

    @Override
    @Transactional
    public PostCommentResponse addComment(String postUuid, PostCommentRequest request, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        PostComment parent = null;
        if (request.getParentId() != null && !request.getParentId().isBlank()) {
            parent = postCommentRepository.findByUuid(UUID.fromString(request.getParentId())).orElse(null);
        }

        PostComment comment = PostComment.builder()
                .post(post)
                .user(currentUser)
                .content(request.getContent())
                .parent(parent)
                .build();

        comment = postCommentRepository.save(comment);
        
        if (!post.getUser().getId().equals(currentUser.getId())) {
            notificationService.createNotification(
                post.getUser(),
                "New Comment",
                currentUser.getUsername() + " commented on your post.",
                "COMMENT",
                post.getUuid().toString()
            );
        }

        return mapToCommentResponse(comment);
    }

    @Override
    @Transactional
    public void deleteComment(String postUuid, String commentUuid, User currentUser) {
        PostComment comment = postCommentRepository.findByUuid(UUID.fromString(commentUuid))
                .orElseThrow(() -> new NotFoundException("Comment not found", "TM_221"));

        if (!comment.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot delete comment of another user", "TM_103");
        }

        comment.setDeleted(true);
        postCommentRepository.save(comment);
    }

    @Override
    @Transactional
    public PostCommentResponse editComment(String postUuid, String commentUuid, PostCommentRequest request, User currentUser) {
        PostComment comment = postCommentRepository.findByUuid(UUID.fromString(commentUuid))
                .orElseThrow(() -> new NotFoundException("Comment not found", "TM_221"));

        if (!comment.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot edit comment of another user", "TM_103");
        }

        comment.setContent(request.getContent());
        comment = postCommentRepository.save(comment);
        return mapToCommentResponse(comment);
    }

    @Override
    @Transactional
    public void bookmarkPost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        if (postBookmarkRepository.existsByPostAndUser(post, currentUser)) {
            return;
        }

        PostBookmark bookmark = PostBookmark.builder().post(post).user(currentUser).build();
        postBookmarkRepository.save(bookmark);
    }

    @Override
    @Transactional
    public void unbookmarkPost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        postBookmarkRepository.findByPostAndUser(post, currentUser)
                .ifPresent(postBookmarkRepository::delete);
    }

    private PostResponse mapToPostResponse(Post post, User currentUser) {
        List<PostMediaResponse> mediaRes = post.getMedia().stream()
                .map(m -> PostMediaResponse.builder()
                        .id(m.getUuid().toString())
                        .mediaUrl(m.getMediaUrl())
                        .mediaType(m.getMediaType())
                        .build())
                .collect(Collectors.toList());

        List<PostCommentResponse> commentsRes = postCommentRepository.findByPostAndParentNullOrderByCreatedAtAsc(post).stream()
                .map(this::mapToCommentResponse)
                .collect(Collectors.toList());

        boolean liked = postLikeRepository.existsByPostAndUser(post, currentUser);
        boolean bookmarked = postBookmarkRepository.existsByPostAndUser(post, currentUser);

        return PostResponse.builder()
                .id(post.getUuid().toString())
                .user(userMapper.toAuthUserResponse(post.getUser()))
                .content(post.getContent())
                .media(mediaRes)
                .likesCount(post.getLikes().size())
                .commentsCount(commentsRes.size())
                .likedByMe(liked)
                .bookmarkedByMe(bookmarked)
                .createdAt(post.getCreatedAt().toString())
                .comments(commentsRes)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostCommentResponse> getComments(String postUuid, Pageable pageable) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        return postCommentRepository.findByPostAndParentIsNull(post, pageable)
                .map(this::mapToCommentResponse);
    }

    private PostCommentResponse mapToCommentResponse(PostComment c) {
        return PostCommentResponse.builder()
                .id(c.getUuid().toString())
                .userId(c.getUser().getUuid().toString())
                .username(c.getUser().getUsername())
                .name(c.getUser().getName())
                .profileImage(c.getUser().getProfileImage())
                .content(c.getContent())
                .createdAt(c.getCreatedAt().toString())
                .build();
    }
}
