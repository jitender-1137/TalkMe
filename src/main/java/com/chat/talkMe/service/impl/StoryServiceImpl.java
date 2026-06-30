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

    @Override
    @Transactional
    public StoryResponse createStory(StoryRequest request, User currentUser) {
        // Stories are publicly visible — the caption must be clean. (The media image
        // is hard-blocked at upload time for the "story" context in UploadController.)
        if (moderationService.moderateText(request.getCaption()).isExplicit()) {
            throw new com.chat.talkMe.exception.ContentModerationException(
                    "Your story caption contains content that violates our community guidelines.");
        }
        Story story = Story.builder()
                .user(currentUser)
                .mediaUrl(request.getMediaUrl())
                .caption(request.getCaption())
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

        return StoryResponse.builder()
                .id(story.getUuid().toString())
                .user(userMapper.toAuthUserResponse(story.getUser()))
                .mediaUrl(story.getMediaUrl())
                .caption(story.getCaption())
                .expiresAt(story.getExpiresAt().toString())
                .createdAt(story.getCreatedAt().toString())
                .viewedByMe(viewed)
                .build();
    }
}
