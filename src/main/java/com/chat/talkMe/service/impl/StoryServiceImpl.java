package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.StoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryServiceImpl implements StoryService {

    private final StoryRepository storyRepository;
    private final StoryViewRepository storyViewRepository;
    private final UserMapper userMapper;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;
    private final UserSettingRepository userSettingRepository;
    private final PhotoMusicMuxer photoMusicMuxer;
    private final com.chat.talkMe.repository.UserFollowRepository userFollowRepository;
    private final com.chat.talkMe.service.NotificationService notificationService;
    private final com.chat.talkMe.service.FeatureAccessService featureAccessService;

    @Override
    @Transactional
    public StoryResponse createStory(StoryRequest request, User currentUser) {
        // Stories are publicly visible — the caption must be clean. (The media image
        // is hard-blocked at upload time for the "story" context in UploadController.)
        if (moderationService.moderateText(request.getCaption()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your story caption contains content that violates our community guidelines.");
        }

        com.chat.talkMe.enums.StoryKind kind = "VOICE".equalsIgnoreCase(
                request.getKind() == null ? "" : request.getKind().trim())
                ? com.chat.talkMe.enums.StoryKind.VOICE
                : com.chat.talkMe.enums.StoryKind.VISUAL;

        String mediaUrl = request.getMediaUrl();
        var audioReq = request.getAudio();

        if (kind == com.chat.talkMe.enums.StoryKind.VOICE) {
            // Voice status (feature #21): mediaUrl IS the voice clip. Gate + validate; no image
            // moderation / photo-music muxing applies (it's pure audio).
            if (!featureAccessService.hasAccess(currentUser, com.chat.talkMe.enums.FeatureKey.VOICE_STATUS)) {
                throw new com.chat.talkMe.exception.FeatureLockedException();
            }
            if (!com.chat.talkMe.validator.AudioValidator.hasAudioExtension(mediaUrl)) {
                throw new com.chat.talkMe.exception.BadRequestException(
                        "A voice status must be an audio clip", "TM_232");
            }
        } else {
            // Photo + music story → merge into an auto-playing video (Instagram-style) so
            // the sound plays with the story like a video. Skip if the media is already a
            // video; fall back to the plain image if muxing is unavailable.
            boolean alreadyVideo = mediaUrl != null && mediaUrl.toLowerCase().contains(".mp4");
            if (audioReq != null && audioReq.getAudioUrl() != null && mediaUrl != null && !alreadyVideo) {
                int start = audioReq.getAudioStartSec() == null ? 0 : audioReq.getAudioStartSec();
                int clip = audioReq.getAudioClipSeconds() == null ? 15 : audioReq.getAudioClipSeconds();
                String video = photoMusicMuxer.muxPhotoWithMusic(mediaUrl, audioReq.getAudioUrl(), start, clip);
                if (video != null) mediaUrl = video;
            }
        }

        com.chat.talkMe.enums.PostAudience audience = com.chat.talkMe.enums.PostAudience.EVERYONE;
        if (request.getAudience() != null && "FRIENDS".equalsIgnoreCase(request.getAudience().trim())) {
            audience = com.chat.talkMe.enums.PostAudience.FRIENDS;
        }

        Story story = Story.builder()
                .user(currentUser)
                .mediaUrl(mediaUrl)
                .caption(request.getCaption())
                .audience(audience)
                .kind(kind)
                .audio(request.getAudio() != null ? request.getAudio().toEntity() : null)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        story = storyRepository.save(story);
        log.info("Story posted successfully by {}", currentUser.getUsername());

        // Instagram-style: tell the author's whole network (followers + following) they
        // posted a new story. Best-effort — never fails the story creation.
        try {
            notificationService.notifyFollowersAndFollowing(
                    currentUser,
                    "New story",
                    currentUser.getName() + " added to their story.",
                    "STORY",
                    story.getUuid().toString(),
                    story.getMediaUrl());
        } catch (Exception e) {
            log.warn("Failed to fan out new-story notification for {}", currentUser.getUsername(), e);
        }

        return mapToStoryResponse(story, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getActiveStories(User currentUser) {
        List<Story> activeStories = storyRepository.findActiveStories(Instant.now());
        return activeStories.stream()
                .filter(story -> canViewStory(story, currentUser))
                .map(story -> mapToStoryResponse(story, currentUser))
                .collect(Collectors.toList());
    }

    /**
     * A FRIENDS story is visible to its author or an accepted follower/following (an
     * ACCEPTED follow in either direction); EVERYONE stories are visible to all.
     */
    private boolean canViewStory(Story story, User viewer) {
        if (story.getAudience() != com.chat.talkMe.enums.PostAudience.FRIENDS) return true;
        if (viewer == null) return false;
        if (story.getUser().getId().equals(viewer.getId())) return true;
        return userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(viewer, story.getUser(), "ACCEPTED")
                || userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(story.getUser(), viewer, "ACCEPTED");
    }

    @Override
    @Transactional
    public void deleteStory(String storyUuid, User currentUser) {
        Story story = storyRepository.findByUuid(UUID.fromString(storyUuid))
                .orElseThrow(() -> new NotFoundException("Story not found", "TM_231"));

        if (!story.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot delete story of another user", "TM_103");
        }

        story.setDeleted(true);
        storyRepository.save(story);
    }

    @Override
    @Transactional
    public void viewStory(String storyUuid, User currentUser) {
        Story story = storyRepository.findByUuid(UUID.fromString(storyUuid))
                .orElseThrow(() -> new NotFoundException("Story not found", "TM_231"));

        // The owner opening their own story must NOT count as a view (Instagram-style).
        if (story.getUser().getId().equals(currentUser.getId())) {
            return;
        }

        if (storyViewRepository.existsByStoryAndUser(story, currentUser)) {
            return;
        }

        StoryView view = StoryView.builder()
                .story(story)
                .user(currentUser)
                .viewedAt(Instant.now())
                .build();
        storyViewRepository.save(view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.chat.talkMe.dto.response.StoryViewerResponse> getStoryViewers(String storyUuid, User currentUser) {
        Story story = storyRepository.findByUuid(UUID.fromString(storyUuid))
                .orElseThrow(() -> new NotFoundException("Story not found", "TM_231"));

        if (!story.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot inspect viewers of another user's story", "TM_103");
        }

        // Scoped query (most-recent first) instead of scanning every StoryView row.
        return storyViewRepository.findByStoryOrderByViewedAtDesc(story).stream()
                .map(v -> com.chat.talkMe.dto.response.StoryViewerResponse.builder()
                        .user(userMapper.toAuthUserResponse(v.getUser()))
                        .viewedAt(v.getViewedAt() != null ? v.getViewedAt().toString() : null)
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getMyStories(User currentUser) {
        // All of the current user's non-deleted stories, incl. expired (archive).
        return storyRepository.findAllByUser(currentUser).stream()
                .map(story -> mapToStoryResponse(story, currentUser))
                .collect(Collectors.toList());
    }

    private StoryResponse mapToStoryResponse(Story story, User currentUser) {
        boolean viewed = storyViewRepository.existsByStoryAndUser(story, currentUser);
        boolean isOwner = story.getUser().getId().equals(currentUser.getId());

        AuthUserResponse owner = userMapper.toAuthUserResponse(story.getUser());
        owner.setMessagingFriendsOnly(userSettingRepository.findByUser(story.getUser())
                .map(s -> s.getMessagingPrivacy() == com.chat.talkMe.enums.MessagingPrivacy.FRIENDS_ONLY)
                .orElse(false));

        return StoryResponse.builder()
                .id(story.getUuid().toString())
                .user(owner)
                .mediaUrl(story.getMediaUrl())
                .caption(story.getCaption())
                .expiresAt(story.getExpiresAt().toString())
                .createdAt(story.getCreatedAt().toString())
                .viewedByMe(viewed)
                // Total distinct viewers — only meaningful to the owner, but cheap to
                // always include (the owner's UI reads it; others simply ignore it).
                .viewCount(storyViewRepository.countByStory(story))
                .owner(isOwner)
                .expired(story.isExpired())
                .audience(story.getAudience() != null ? story.getAudience().name() : "EVERYONE")
                .audio(com.chat.talkMe.dto.response.AudioTrackDto.from(story.getAudio()))
                .kind(story.getKind() != null ? story.getKind().name() : "VISUAL")
                .build();
    }
}
