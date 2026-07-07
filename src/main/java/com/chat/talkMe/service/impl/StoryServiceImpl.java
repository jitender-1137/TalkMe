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

    @Override
    @Transactional
    public StoryResponse createStory(StoryRequest request, User currentUser) {
        // Stories are publicly visible — the caption must be clean. (The media image
        // is hard-blocked at upload time for the "story" context in UploadController.)
        if (moderationService.moderateText(request.getCaption()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your story caption contains content that violates our community guidelines.");
        }
        // Photo + music story → merge into an auto-playing video (Instagram-style) so
        // the sound plays with the story like a video. Skip if the media is already a
        // video; fall back to the plain image if muxing is unavailable.
        String mediaUrl = request.getMediaUrl();
        var audioReq = request.getAudio();
        boolean alreadyVideo = mediaUrl != null && mediaUrl.toLowerCase().contains(".mp4");
        if (audioReq != null && audioReq.getAudioUrl() != null && mediaUrl != null && !alreadyVideo) {
            int start = audioReq.getAudioStartSec() == null ? 0 : audioReq.getAudioStartSec();
            int clip = audioReq.getAudioClipSeconds() == null ? 15 : audioReq.getAudioClipSeconds();
            String video = photoMusicMuxer.muxPhotoWithMusic(mediaUrl, audioReq.getAudioUrl(), start, clip);
            if (video != null) mediaUrl = video;
        }

        Story story = Story.builder()
                .user(currentUser)
                .mediaUrl(mediaUrl)
                .caption(request.getCaption())
                .audio(request.getAudio() != null ? request.getAudio().toEntity() : null)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        story = storyRepository.save(story);
        log.info("Story posted successfully by {}", currentUser.getUsername());

        return mapToStoryResponse(story, currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getActiveStories(User currentUser) {
        List<Story> activeStories = storyRepository.findActiveStories(Instant.now());
        return activeStories.stream()
                .map(story -> mapToStoryResponse(story, currentUser))
                .collect(Collectors.toList());
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
    public List<AuthUserResponse> getStoryViewers(String storyUuid, User currentUser) {
        Story story = storyRepository.findByUuid(UUID.fromString(storyUuid))
                .orElseThrow(() -> new NotFoundException("Story not found", "TM_231"));

        if (!story.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot inspect viewers of another user's story", "TM_103");
        }

        // Fetch story views in database (JPA relationships)
        // Set up in SQL but can query directly:
        // We will fetch from database using standard list view
        return storyViewRepository.findAll().stream()
                .filter(v -> v.getStory().getId().equals(story.getId()))
                .map(v -> userMapper.toAuthUserResponse(v.getUser()))
                .collect(Collectors.toList());
    }

    private StoryResponse mapToStoryResponse(Story story, User currentUser) {
        boolean viewed = storyViewRepository.existsByStoryAndUser(story, currentUser);

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
                .audio(com.chat.talkMe.dto.response.AudioTrackDto.from(story.getAudio()))
                .build();
    }
}
