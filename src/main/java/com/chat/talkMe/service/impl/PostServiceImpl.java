package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
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
    private final PostCommentLikeRepository postCommentLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;

    @Override
    @Transactional
    public PostResponse createPost(PostRequest request, User currentUser) {
        // Public feed is hard-blocked: explicit captions are rejected outright.
        if (moderationService.moderateText(request.getContent()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your post contains content that violates our community guidelines.");
        }
        Post post = Post.builder()
                .user(currentUser)
                .content(request.getContent())
                .shortCode(com.chat.talkMe.util.ShortCodes.unique(c -> !postRepository.existsByShortCode(c)))
                .build();

        post = postRepository.save(post);

        if (request.getMedia() != null) {
            int order = 0;
            for (var mediaReq : request.getMedia()) {
                // Public feed → NSFW media is hard-blocked.
                java.nio.file.Path mediaPath = resolveStoredMedia(mediaReq.getMediaUrl());
                if (mediaPath != null) {
                    boolean isVideo = "VIDEO".equalsIgnoreCase(mediaReq.getMediaType());
                    var mt = isVideo ? com.chat.talkMe.enums.MessageType.VIDEO : com.chat.talkMe.enums.MessageType.IMAGE;
                    if (moderationService.moderateMedia(mediaPath, mt).isExplicit()) {
                        throw new com.chat.talkMe.exception.ContentModerationException(
                                "Your post contains media that violates our community guidelines.");
                    }
                }
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
    @Transactional(readOnly = true)
    public PostResponse getPost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        if (post.isDeleted()) {
            throw new NotFoundException("Post not found", "TM_211");
        }
        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse getPostByShortCode(String shortCode, User currentUser) {
        Post post = postRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        if (post.isDeleted()) {
            throw new NotFoundException("Post not found", "TM_211");
        }
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
    @Transactional(readOnly = true)
    public Page<AuthUserResponse> getPostLikes(String postUuid, Pageable pageable, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        return postLikeRepository.findByPost(post, pageable)
                .map(like -> {
                    AuthUserResponse res = userMapper.toAuthUserResponse(like.getUser());
                    res.setMessagingFriendsOnly(isFriendsOnly(like.getUser()));
                    return res;
                });
    }

    @Override
    @Transactional
    public PostCommentResponse addComment(String postUuid, PostCommentRequest request, User currentUser) {
        // Comments are public → hard-block explicit content.
        if (moderationService.moderateText(request.getContent()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your comment contains content that violates our community guidelines.");
        }
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

        return mapToCommentResponse(comment, currentUser);
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
    public void likeComment(String postUuid, String commentUuid, User currentUser) {
        PostComment comment = postCommentRepository.findByUuid(UUID.fromString(commentUuid))
                .orElseThrow(() -> new NotFoundException("Comment not found", "TM_221"));

        if (postCommentLikeRepository.existsByCommentAndUser(comment, currentUser)) {
            return; // already liked — idempotent
        }

        PostCommentLike like = PostCommentLike.builder().comment(comment).user(currentUser).build();
        postCommentLikeRepository.save(like);

        if (!comment.getUser().getId().equals(currentUser.getId())) {
            notificationService.createNotification(
                comment.getUser(),
                "New Like",
                currentUser.getUsername() + " liked your comment.",
                "LIKE",
                comment.getPost().getUuid().toString()
            );
        }
    }

    @Override
    @Transactional
    public void unlikeComment(String postUuid, String commentUuid, User currentUser) {
        PostComment comment = postCommentRepository.findByUuid(UUID.fromString(commentUuid))
                .orElseThrow(() -> new NotFoundException("Comment not found", "TM_221"));

        postCommentLikeRepository.findByCommentAndUser(comment, currentUser)
                .ifPresent(postCommentLikeRepository::delete);
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
        return mapToCommentResponse(comment, currentUser);
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

    /** Resolve a stored media URL to an on-disk path for moderation (path-traversal safe). */
    private java.nio.file.Path resolveStoredMedia(String mediaUrl) {
        try {
            String candidate = extractStoredPath(mediaUrl);
            if (candidate == null || candidate.isBlank()) return null;
            java.nio.file.Path base = java.nio.file.Paths.get("/opt/media/talkMe").toRealPath();
            java.nio.file.Path real = java.nio.file.Paths.get(candidate).normalize().toRealPath();
            return real.startsWith(base) ? real : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Recover the on-disk path from a media URL. The web client rewrites stored
     * paths to "{base}/uploads/media?path={url-encoded absolute path}", so the URL
     * is normally NOT a raw path. Prefer the {@code path=} query parameter; else
     * accept an absolute media path, or resolve a bare filename under the root.
     */
    private String extractStoredPath(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return null;
        }
        int idx = mediaUrl.indexOf("path=");
        if (idx >= 0) {
            String raw = mediaUrl.substring(idx + "path=".length());
            int amp = raw.indexOf('&');
            if (amp >= 0) {
                raw = raw.substring(0, amp);
            }
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (mediaUrl.startsWith("/opt/media/")) {
            return mediaUrl;
        }
        String name = mediaUrl;
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? null : "/opt/media/talkMe/" + name;
    }

    /** Whether a user restricts messaging to friends (drives the avatar lock badge). */
    private boolean isFriendsOnly(User user) {
        return userSettingRepository.findByUser(user)
                .map(s -> s.getMessagingPrivacy() == com.chat.talkMe.enums.MessagingPrivacy.FRIENDS_ONLY)
                .orElse(false);
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
                .map(c -> mapToCommentResponse(c, currentUser))
                .collect(Collectors.toList());

        boolean liked = postLikeRepository.existsByPostAndUser(post, currentUser);
        boolean bookmarked = postBookmarkRepository.existsByPostAndUser(post, currentUser);

        AuthUserResponse author = userMapper.toAuthUserResponse(post.getUser());
        author.setMessagingFriendsOnly(isFriendsOnly(post.getUser()));

        return PostResponse.builder()
                .id(post.getUuid().toString())
                .shortCode(post.getShortCode())
                .user(author)
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
    public Page<PostCommentResponse> getComments(String postUuid, Pageable pageable, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        return postCommentRepository.findByPostAndParentIsNull(post, pageable)
                .map(c -> mapToCommentResponse(c, currentUser));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostCommentResponse> getReplies(String postUuid, String commentUuid, Pageable pageable, User currentUser) {
        PostComment parent = postCommentRepository.findByUuid(UUID.fromString(commentUuid))
                .orElseThrow(() -> new NotFoundException("Comment not found", "TM_221"));

        return postCommentRepository.findReplies(parent, pageable)
                .map(c -> mapToCommentResponse(c, currentUser));
    }

    private PostCommentResponse mapToCommentResponse(PostComment c, User currentUser) {
        return PostCommentResponse.builder()
                .id(c.getUuid().toString())
                .userId(c.getUser().getUuid().toString())
                .username(c.getUser().getUsername())
                .name(c.getUser().getName())
                .profileImage(c.getUser().getProfileImage())
                .content(c.getContent())
                .createdAt(c.getCreatedAt().toString())
                .likesCount(postCommentLikeRepository.countByComment(c))
                .likedByMe(currentUser != null && postCommentLikeRepository.existsByCommentAndUser(c, currentUser))
                .parentId(c.getParent() != null ? c.getParent().getUuid().toString() : null)
                .replyCount(postCommentRepository.countReplies(c))
                .build();
    }
}
