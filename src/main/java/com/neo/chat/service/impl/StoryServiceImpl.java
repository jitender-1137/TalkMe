package com.neo.chat.service.impl;

import com.neo.chat.domain.Story;
import com.neo.chat.domain.StoryView;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.StoryRequest;
import com.neo.chat.dto.response.AudioTrackDto;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.StoryResponse;
import com.neo.chat.dto.response.StoryViewerResponse;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.PostAudience;
import com.neo.chat.enums.StoryKind;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.FeatureLockedException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.repository.StoryRepository;
import com.neo.chat.repository.StoryViewRepository;
import com.neo.chat.repository.UserFollowRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.FeatureAccessService;
import com.neo.chat.service.NotificationService;
import com.neo.chat.service.StoryService;
import com.neo.chat.validator.AudioValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link StoryService} implementation: content moderation, voice-status gating,
 * photo+music muxing, owner-excluded view counting, and viewer-relative response mapping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryServiceImpl implements StoryService {

    private final StoryRepository storyRepository;
    private final StoryViewRepository storyViewRepository;
    private final UserMapper userMapper;
    private final ContentModerationService moderationService;
    private final UserSettingRepository userSettingRepository;
    private final PhotoMusicMuxer photoMusicMuxer;
    private final UserFollowRepository userFollowRepository;
    private final NotificationService notificationService;
    private final FeatureAccessService featureAccessService;

    /**
     * Creates a 24-hour story for the given user.
     *
     * <p>The caption is moderated and rejected if explicit. A VOICE story requires the
     * VOICE_STATUS entitlement and an audio-clip media URL; a visual photo paired with a
     * soundtrack is muxed into an autoplaying video. Followers and following are notified
     * best-effort (a notification failure never fails the post).
     *
     * @param request     the story payload (media URL, caption, audience, kind, optional audio)
     * @param currentUser the authenticated author
     * @return the created story rendered for the author (owner flags populated)
     * @throws ContentModerationException if the caption is explicit
     * @throws FeatureLockedException     if a VOICE story is requested without the VOICE_STATUS
     *                                    entitlement
     * @throws BadRequestException        if a VOICE story's media is not an audio clip
     */
    @Override
    @Transactional
    public StoryResponse createStory(StoryRequest request, User currentUser) {
        // Stories are publicly visible — the caption must be clean. (The media image
        // is hard-blocked at upload time for the "story" context in UploadController.)
        if (moderationService.moderateText(request.getCaption()).explicit()) {
            throw new ContentModerationException(
                    "Your story caption contains content that violates our community guidelines.");
        }

        StoryKind kind = "VOICE".equalsIgnoreCase(
                request.getKind() == null ? "" : request.getKind().trim())
                ? StoryKind.VOICE
                : StoryKind.VISUAL;

        String mediaUrl = request.getMediaUrl();
        var audioReq = request.getAudio();

        if (kind == StoryKind.VOICE) {
            // Voice status (feature #21): mediaUrl IS the voice clip. Gate + validate; no image
            // moderation / photo-music muxing applies (it's pure audio).
            if (!featureAccessService.hasAccess(currentUser, FeatureKey.VOICE_STATUS)) {
                throw new FeatureLockedException();
            }
            // SECURITY: the clip must be an uploaded reference, never an external URL.
            if (com.neo.chat.util.MediaReferences.isExternalUrl(mediaUrl)) {
                throw new BadRequestException("A voice status must be an uploaded audio clip.", "TM_232");
            }
            if (!AudioValidator.hasAudioExtension(mediaUrl)) {
                throw new BadRequestException(
                        "A voice status must be an audio clip", "TM_232");
            }
        } else {
            // SECURITY: the image/video must be an internal storage reference from the upload
            // endpoint, never an external URL (viewer-IP leak / post-publication content swap /
            // moderation bypass — external media is never NSFW-scanned).
            if (com.neo.chat.util.MediaReferences.isExternalUrl(mediaUrl)) {
                throw new BadRequestException("Story media must be an uploaded file.", "TM_232");
            }
            // Photo + music story → merge into an autoplaying video (Instagram-style) so
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

        PostAudience audience = PostAudience.EVERYONE;
        if (request.getAudience() != null && "FRIENDS".equalsIgnoreCase(request.getAudience().trim())) {
            audience = PostAudience.FRIENDS;
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
        log.info("Story posted successfully by {}", currentUser.getUuid());

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
            log.warn("Failed to fan out new-story notification for {}", currentUser.getUuid(), e);
        }

        return mapToStoryResponse(story, currentUser);
    }

    /**
     * Returns every currently-active (non-expired, non-deleted) story visible to the viewer,
     * newest first. FRIENDS-audience stories are included only when the viewer is the author or
     * has an accepted follow relationship in either direction (see {@link #canViewStory}).
     *
     * @param currentUser the viewer
     * @return the visible active stories, newest first (empty when none)
     */
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
     * Applies the audience rule for a single story. EVERYONE stories are visible to
     * all; a FRIENDS story is visible only to its author or to a user with an
     * accepted follow relationship in either direction (follower→author or
     * author→follower).
     *
     * @param story  the story whose visibility is being checked
     * @param viewer the prospective viewer; a {@code null} viewer can only see EVERYONE stories
     * @return {@code true} if the viewer may see the story; otherwise {@code false}
     */
    private boolean canViewStory(Story story, User viewer) {
        if (story.getAudience() != PostAudience.FRIENDS) return true;
        if (viewer == null) return false;
        if (story.getUser().getId().equals(viewer.getId())) return true;
        return userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(viewer, story.getUser(), "ACCEPTED")
                || userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(story.getUser(), viewer, "ACCEPTED");
    }

    /**
     * Soft-deletes a story. Only the author may delete their own story.
     *
     * @param storyUuid   UUID string of the story to delete
     * @param currentUser the caller; must be the story's author
     * @throws NotFoundException  if no such story exists
     * @throws ForbiddenException if the caller is not the author
     */
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

    /**
     * Records a view of a story by the given user. No-op when the viewer is the author (owners
     * never count as viewers, Instagram-style) or has already viewed the story (one view per user).
     *
     * @param storyUuid   UUID string of the story that was opened
     * @param currentUser the viewer
     * @throws NotFoundException if no such story exists
     */
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

    /**
     * Owner-only "seen by" list: the story's viewers with per-viewer view timestamps, most
     * recent first.
     *
     * @param storyUuid   UUID string of the story
     * @param currentUser the caller; must be the story's author
     * @return the viewers, most-recently-viewed first (empty when none)
     * @throws NotFoundException  if no such story exists
     * @throws ForbiddenException if the caller is not the author
     */
    @Override
    @Transactional(readOnly = true)
    public List<StoryViewerResponse> getStoryViewers(String storyUuid, User currentUser) {
        Story story = storyRepository.findByUuid(UUID.fromString(storyUuid))
                .orElseThrow(() -> new NotFoundException("Story not found", "TM_231"));

        if (!story.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot inspect viewers of another user's story", "TM_103");
        }

        // Scoped query (most-recent first) instead of scanning every StoryView row.
        return storyViewRepository.findByStoryOrderByViewedAtDesc(story).stream()
                .map(v -> StoryViewerResponse.builder()
                        .user(userMapper.toAuthUserResponse(v.getUser()))
                        .viewedAt(v.getViewedAt() != null ? v.getViewedAt().toString() : null)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Returns the user's OWN stories, newest first, <strong>including expired</strong> ones —
     * the profile "My Stories" archive.
     *
     * @param currentUser the user whose archive is requested
     * @return the user's non-deleted stories (active and expired), newest first
     */
    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getMyStories(User currentUser) {
        // All the current user's non-deleted stories, incl. expired (archive).
        return storyRepository.findAllByUser(currentUser).stream()
                .map(story -> mapToStoryResponse(story, currentUser))
                .collect(Collectors.toList());
    }

    /**
     * Builds the API response for a story, relative to the requesting user. Populates
     * viewer-specific flags — {@code viewedByMe} and {@code owner} — plus the author's
     * "friends-only messaging" hint and the total distinct {@code viewCount} (only
     * meaningful to the owner's UI, but always included as it's a cheap count).
     *
     * @param story       the story entity to convert
     * @param currentUser the user the response is being rendered for
     * @return the story response with viewer-relative flags populated
     */
    private StoryResponse mapToStoryResponse(Story story, User currentUser) {
        boolean viewed = storyViewRepository.existsByStoryAndUser(story, currentUser);
        boolean isOwner = story.getUser().getId().equals(currentUser.getId());

        AuthUserResponse owner = userMapper.toAuthUserResponse(story.getUser());
        owner.setMessagingFriendsOnly(userSettingRepository.findByUser(story.getUser())
                .map(s -> s.getMessagingPrivacy() == MessagingPrivacy.FRIENDS_ONLY)
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
                .audio(AudioTrackDto.from(story.getAudio()))
                .kind(story.getKind() != null ? story.getKind().name() : "VISUAL")
                .build();
    }
}
