package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.AudioTrackDto;
import com.chat.talkMe.dto.response.PollOptionResponse;
import com.chat.talkMe.dto.response.PollResponse;
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
    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final PollVoteRepository pollVoteRepository;
    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;
    private final PhotoMusicMuxer photoMusicMuxer;
    private final com.chat.talkMe.storage.MediaStorage mediaStorage;
    private final com.chat.talkMe.repository.UserFollowRepository userFollowRepository;
    private final com.chat.talkMe.service.FeatureAccessService featureAccessService;

    /** Temporary-post TTL bounds (feature #22): 5 minutes … 7 days. */
    private static final long TEMP_POST_MIN_SECONDS = 300L;
    private static final long TEMP_POST_MAX_SECONDS = 7L * 24 * 60 * 60;
    /** Reaper page size — bound the per-tick work like OutboxPublisherJob. */
    private static final int EXPIRY_REAP_BATCH = 200;

    @Override
    @Transactional
    public PostResponse createPost(PostRequest request, User currentUser) {
        // A post must carry something: text, media, or a poll. (Text-only is allowed.)
        boolean hasContent = request.getContent() != null && !request.getContent().isBlank();
        boolean hasMedia = request.getMedia() != null && !request.getMedia().isEmpty();
        boolean hasPoll = request.getPoll() != null;
        if (!hasContent && !hasMedia && !hasPoll) {
            throw new BadRequestException("A post must have text, media, or a poll", "TM_230");
        }
        // Public feed is hard-blocked: explicit captions are rejected outright.
        if (moderationService.moderateText(request.getContent()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your post contains content that violates our community guidelines.");
        }
        com.chat.talkMe.enums.PostAudience audience = com.chat.talkMe.enums.PostAudience.EVERYONE;
        if (request.getAudience() != null && "FRIENDS".equalsIgnoreCase(request.getAudience().trim())) {
            audience = com.chat.talkMe.enums.PostAudience.FRIENDS;
        }

        // Temporary post (feature #22): opt-in TTL, gated + clamped server-side.
        java.time.Instant expiresAt = null;
        if (request.getExpiresInSeconds() != null && request.getExpiresInSeconds() > 0) {
            if (!featureAccessService.hasAccess(currentUser, com.chat.talkMe.enums.FeatureKey.TEMPORARY_POSTS)) {
                throw new com.chat.talkMe.exception.FeatureLockedException();
            }
            long ttl = Math.max(TEMP_POST_MIN_SECONDS,
                    Math.min(TEMP_POST_MAX_SECONDS, request.getExpiresInSeconds()));
            expiresAt = java.time.Instant.now().plusSeconds(ttl);
        }

        if (request.getCaption() != null && !request.getCaption().isBlank()
                && moderationService.moderateText(request.getCaption()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your post contains content that violates our community guidelines.");
        }

        Post post = Post.builder()
                .user(currentUser)
                .content(request.getContent())
                .richContent(request.getRichContent())
                .caption(request.getCaption())
                .audience(audience)
                .expiresAt(expiresAt)
                .shortCode(com.chat.talkMe.util.ShortCodes.unique(c -> !postRepository.existsByShortCode(c)))
                .build();

        post = postRepository.save(post);

        // Instagram-style photo + music → merge into an auto-playing video. When the
        // post is a SINGLE image with a soundtrack, mux the still image + trimmed clip
        // into an MP4 so the sound plays with the post like an uploaded video. Falls
        // back silently to the plain image (+ audio attribution) if muxing is
        // unavailable.
        // The mediaUrl of our own muxed output — moderation is skipped for it (the
        // SOURCE image was already moderated below, before muxing; re-running video
        // frame-extraction moderation on the fresh mp4 is wasteful and was breaking
        // photo+music posts).
        String muxedMediaUrl = null;
        if (request.getAudio() != null && request.getAudio().getAudioUrl() != null
                && request.getMedia() != null && request.getMedia().size() == 1) {
            var only = request.getMedia().get(0);
            boolean isImage = !"VIDEO".equalsIgnoreCase(only.getMediaType());
            if (isImage) {
                // Moderate the still image up front (public feed hard-blocks NSFW).
                try (var local = mediaStorage.localCopy(only.getMediaUrl()).orElse(null)) {
                    if (local != null && moderationService.moderateMedia(local.path(), com.chat.talkMe.enums.MessageType.IMAGE).isExplicit()) {
                        throw new com.chat.talkMe.exception.ContentModerationException(
                                "Your post contains media that violates our community guidelines.");
                    }
                }
                var a = request.getAudio();
                int start = a.getAudioStartSec() == null ? 0 : a.getAudioStartSec();
                int clip = a.getAudioClipSeconds() == null ? 15 : a.getAudioClipSeconds();
                String video = photoMusicMuxer.muxPhotoWithMusic(only.getMediaUrl(), a.getAudioUrl(), start, clip);
                if (video != null) {
                    only.setMediaUrl(video);
                    only.setMediaType("VIDEO");
                    muxedMediaUrl = video;
                }
            }
        }

        if (request.getMedia() != null) {
            int order = 0;
            for (var mediaReq : request.getMedia()) {
                // Public feed → NSFW media is hard-blocked. Skip our own muxed output
                // (its source image was already moderated above).
                boolean isMuxedOutput = mediaReq.getMediaUrl() != null && mediaReq.getMediaUrl().equals(muxedMediaUrl);
                if (!isMuxedOutput) {
                    try (var local = mediaStorage.localCopy(mediaReq.getMediaUrl()).orElse(null)) {
                        if (local != null) {
                            boolean isVideo = "VIDEO".equalsIgnoreCase(mediaReq.getMediaType());
                            var mt = isVideo ? com.chat.talkMe.enums.MessageType.VIDEO : com.chat.talkMe.enums.MessageType.IMAGE;
                            if (moderationService.moderateMedia(local.path(), mt).isExplicit()) {
                                throw new com.chat.talkMe.exception.ContentModerationException(
                                        "Your post contains media that violates our community guidelines.");
                            }
                        }
                    }
                }
                PostMedia media = PostMedia.builder()
                        .post(post)
                        .mediaUrl(mediaReq.getMediaUrl())
                        .mediaType(mediaReq.getMediaType())
                        .orderIndex(order++)
                        .trimStartSec(mediaReq.getTrimStartSec())
                        .trimEndSec(mediaReq.getTrimEndSec())
                        .muted(mediaReq.getMuted())
                        .coverImageUrl(mediaReq.getCoverImageUrl())
                        .filterName(mediaReq.getFilterName())
                        .build();
                postMediaRepository.save(media);
                post.getMedia().add(media);
            }
        }

        // Poll posts: build the poll + its options alongside the post.
        if (request.getPoll() != null) {
            var pollReq = request.getPoll();
            List<String> options = pollReq.getOptions() == null ? List.of() : pollReq.getOptions().stream()
                    .filter(o -> o != null && !o.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toList());
            if (options.size() < 2) {
                throw new BadRequestException("A poll needs at least 2 options", "TM_225");
            }
            // Poll question + options are public → hard-block explicit content.
            if (moderationService.moderateText(pollReq.getQuestion()).isExplicit()
                    || options.stream().anyMatch(o -> moderationService.moderateText(o).isExplicit())) {
                throw new com.chat.talkMe.exception.ContentModerationException(
                        "Your poll contains content that violates our community guidelines.");
            }

            Poll poll = Poll.builder()
                    .post(post)
                    .question(pollReq.getQuestion().trim())
                    .build();
            poll = pollRepository.save(poll);

            int order = 0;
            for (String text : options) {
                PollOption option = PollOption.builder()
                        .poll(poll)
                        .text(text)
                        .orderIndex(order++)
                        .build();
                pollOptionRepository.save(option);
                poll.getOptions().add(option);
            }
            post.setPoll(poll);
        }

        // Optional soundtrack.
        if (request.getAudio() != null) {
            AudioTrack track = request.getAudio().toEntity();
            if (track != null) {
                post.setAudio(track);
                postRepository.save(post);
            }
        }

        log.info("Post created successfully by {}", currentUser.getUsername());

        // Instagram-style: tell the author's whole network (followers + following) that
        // they shared a new post. Best-effort — a notification failure must not fail the
        // post creation.
        try {
            notificationService.notifyFollowersAndFollowing(
                    currentUser,
                    "New post",
                    currentUser.getName() + " shared a new post.",
                    "POST",
                    post.getUuid().toString(),
                    firstThumb(post));
        } catch (Exception e) {
            log.warn("Failed to fan out new-post notification for {}", currentUser.getUsername(), e);
        }

        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional
    public PostResponse votePoll(String postUuid, String optionUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        Poll poll = post.getPoll();
        if (poll == null) {
            throw new BadRequestException("This post is not a poll", "TM_226");
        }
        PollOption option = pollOptionRepository.findByUuid(UUID.fromString(optionUuid))
                .orElseThrow(() -> new NotFoundException("Poll option not found", "TM_227"));
        if (!option.getPoll().getId().equals(poll.getId())) {
            throw new BadRequestException("Option does not belong to this poll", "TM_228");
        }

        var existing = pollVoteRepository.findByPollAndUser(poll, currentUser);
        if (existing.isPresent()) {
            PollVote vote = existing.get();
            if (vote.getOption().getId().equals(option.getId())) {
                // Tapping the current choice again retracts the vote (toggle off).
                pollVoteRepository.delete(vote);
            } else {
                // Switch the vote to the newly chosen option.
                vote.setOption(option);
                pollVoteRepository.save(vote);
            }
        } else {
            pollVoteRepository.save(PollVote.builder()
                    .poll(poll)
                    .option(option)
                    .user(currentUser)
                    .build());
            // Notify the poll owner of a NEW vote (not on toggle-off / switch), with the
            // voter + chosen option — Instagram-style detail. Skip self-votes.
            if (!post.getUser().getId().equals(currentUser.getId())) {
                notificationService.createNotification(
                        post.getUser(),
                        "New poll vote",
                        currentUser.getName() + " voted “" + option.getText() + "” on your poll.",
                        "POLL",
                        post.getUuid().toString(),
                        currentUser,
                        firstThumb(post));
            }
        }

        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse getPost(String postUuid, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        if (post.isDeleted() || post.isExpired() || !canViewPost(post, currentUser)) {
            throw new NotFoundException("Post not found", "TM_211");
        }
        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse getPostByShortCode(String shortCode, User currentUser) {
        Post post = postRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));
        if (post.isDeleted() || post.isExpired() || !canViewPost(post, currentUser)) {
            throw new NotFoundException("Post not found", "TM_211");
        }
        return mapToPostResponse(post, currentUser);
    }

    /**
     * A FRIENDS-only post is viewable by its author or an accepted friend (an
     * ACCEPTED follow in either direction); EVERYONE posts are viewable by all.
     * Non-viewers get a 404 (don't reveal the post exists).
     */
    /** First displayable thumbnail for a post (video cover preferred), or null for
     *  a text-only post. Used as the Instagram-style thumbnail on notifications. */
    private String firstThumb(Post post) {
        if (post.getMedia() == null || post.getMedia().isEmpty()) {
            return null;
        }
        var m = post.getMedia().get(0);
        return m.getCoverImageUrl() != null && !m.getCoverImageUrl().isBlank()
                ? m.getCoverImageUrl()
                : m.getMediaUrl();
    }

    private boolean canViewPost(Post post, User viewer) {
        if (post.getAudience() != com.chat.talkMe.enums.PostAudience.FRIENDS) return true;
        if (viewer == null) return false;
        if (post.getUser().getId().equals(viewer.getId())) return true;
        return userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(viewer, post.getUser(), "ACCEPTED")
                || userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(post.getUser(), viewer, "ACCEPTED");
    }

    @Override
    @Transactional
    public PostResponse updatePost(String postUuid, PostRequest request, User currentUser) {
        Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_211"));

        if (!post.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot edit post of another user", "TM_103");
        }

        // Update only the field the client sent: media/background captions live in
        // `content`; a text post's caption is the separate `caption` field (its
        // formatted body is left untouched on edit).
        if (request.getContent() != null) {
            if (moderationService.moderateText(request.getContent()).isExplicit()) {
                throw new com.chat.talkMe.exception.ContentModerationException(
                        "Your post contains content that violates our community guidelines.");
            }
            post.setContent(request.getContent());
        }
        if (request.getCaption() != null) {
            if (!request.getCaption().isBlank()
                    && moderationService.moderateText(request.getCaption()).isExplicit()) {
                throw new com.chat.talkMe.exception.ContentModerationException(
                        "Your post contains content that violates our community guidelines.");
            }
            post.setCaption(request.getCaption());
        }
        post = postRepository.save(post);
        log.info("Post {} caption updated by {}", postUuid, currentUser.getUsername());
        return mapToPostResponse(post, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostResponse> getFeed(Pageable pageable, User currentUser) {
        // Followers-only (FRIENDS) posts must not leak into the global explore feed:
        // only the author + accepted followers/following see them; EVERYONE posts show
        // to all. The feed endpoint is authenticated, so currentUser is present.
        Page<Post> posts = postRepository.findVisibleFeedFor(currentUser, pageable);
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

        // Hide the target's FRIENDS-only posts from viewers who aren't the author or
        // an accepted friend.
        Page<Post> posts = postRepository.findProfileFeedFor(targetUser, currentUser, pageable);
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
    public int reapExpiredPosts(java.time.Instant now) {
        // Soft-delete expired temporary posts (consistent with deletePost). The feed queries
        // already hide them the instant expiresAt passes; this frees them from every listing
        // and count. Bounded per tick; a backlog drains over successive runs.
        List<Post> expired = postRepository.findExpiredActive(
                now, org.springframework.data.domain.PageRequest.of(0, EXPIRY_REAP_BATCH));
        for (Post post : expired) {
            post.setDeleted(true);
        }
        if (!expired.isEmpty()) {
            postRepository.saveAll(expired);
        }
        return expired.size();
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
                "New like",
                currentUser.getName() + " liked your post.",
                "LIKE",
                post.getUuid().toString(),
                currentUser,
                firstThumb(post)
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

        String snippet = request.getContent() == null ? "" : request.getContent().trim();
        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "…";
        String quoted = snippet.isBlank() ? "." : ": “" + snippet + "”";

        // Notify the post owner (Instagram-style, with the commenter + a snippet). Skip
        // self-comments.
        if (!post.getUser().getId().equals(currentUser.getId())) {
            notificationService.createNotification(
                post.getUser(),
                "New comment",
                currentUser.getName() + " commented on your post" + quoted,
                "COMMENT",
                post.getUuid().toString(),
                currentUser,
                firstThumb(post)
            );
        }

        // If this is a reply, also notify the parent comment's author (unless they're the
        // commenter or the post owner already notified above).
        if (parent != null && parent.getUser() != null
                && !parent.getUser().getId().equals(currentUser.getId())
                && !parent.getUser().getId().equals(post.getUser().getId())) {
            notificationService.createNotification(
                parent.getUser(),
                "New reply",
                currentUser.getName() + " replied to your comment" + quoted,
                "COMMENT",
                post.getUuid().toString(),
                currentUser,
                firstThumb(post)
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
                "New like",
                currentUser.getName() + " liked your comment.",
                "LIKE",
                comment.getPost().getUuid().toString(),
                currentUser,
                firstThumb(comment.getPost())
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
                        .trimStartSec(m.getTrimStartSec())
                        .trimEndSec(m.getTrimEndSec())
                        .muted(m.getMuted())
                        .coverImageUrl(m.getCoverImageUrl())
                        .filterName(m.getFilterName())
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
                .richContent(post.getRichContent())
                .caption(post.getCaption())
                .media(mediaRes)
                .likesCount(post.getLikes().size())
                .commentsCount(commentsRes.size())
                .likedByMe(liked)
                .bookmarkedByMe(bookmarked)
                .createdAt(post.getCreatedAt().toString())
                .comments(commentsRes)
                .poll(mapToPollResponse(post.getPoll(), currentUser))
                .audio(AudioTrackDto.from(post.getAudio()))
                .audience(post.getAudience() != null ? post.getAudience().name() : "EVERYONE")
                .expiresAt(post.getExpiresAt() != null ? post.getExpiresAt().toString() : null)
                .build();
    }

    /** Map a poll (or null) to its response, resolving per-option counts and the caller's vote. */
    private PollResponse mapToPollResponse(Poll poll, User currentUser) {
        if (poll == null) {
            return null;
        }
        String myVoteOptionId = pollVoteRepository.findByPollAndUser(poll, currentUser)
                .map(v -> v.getOption().getUuid().toString())
                .orElse(null);

        List<PollOptionResponse> optionRes = poll.getOptions().stream()
                .map(o -> PollOptionResponse.builder()
                        .id(o.getUuid().toString())
                        .text(o.getText())
                        .votes(pollVoteRepository.countByOption(o))
                        .votedByMe(o.getUuid().toString().equals(myVoteOptionId))
                        .build())
                .collect(Collectors.toList());

        return PollResponse.builder()
                .id(poll.getUuid().toString())
                .question(poll.getQuestion())
                .totalVotes(pollVoteRepository.countByPoll(poll))
                .myVoteOptionId(myVoteOptionId)
                .options(optionRes)
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
