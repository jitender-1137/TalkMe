package com.chat.talkMe.service;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.StoryView;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.StoryViewerResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.StoryRepository;
import com.chat.talkMe.repository.StoryViewRepository;
import com.chat.talkMe.service.impl.StoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for the story <b>view-tracking</b> logic added with the
 * "seen by" feature — {@link StoryServiceImpl#viewStory} and
 * {@link StoryServiceImpl#getStoryViewers}. All 9 collaborators are mocked;
 * only the two repositories and the user mapper actually participate here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoryServiceImpl — view tracking")
class StoryServiceImplViewTest {

    private static final String STORY_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock private StoryRepository storyRepository;
    @Mock private StoryViewRepository storyViewRepository;
    @Mock private UserMapper userMapper;
    @Mock private com.chat.talkMe.moderation.ContentModerationService moderationService;
    @Mock private com.chat.talkMe.repository.UserSettingRepository userSettingRepository;
    @Mock private com.chat.talkMe.service.impl.PhotoMusicMuxer photoMusicMuxer;
    @Mock private com.chat.talkMe.repository.UserFollowRepository userFollowRepository;
    @Mock private com.chat.talkMe.service.NotificationService notificationService;
    @Mock private com.chat.talkMe.service.FeatureAccessService featureAccessService;

    @InjectMocks
    private StoryServiceImpl storyService;

    private User owner;
    private User viewer;
    private Story story;

    @BeforeEach
    void setUp() {
        owner = User.builder().username("owner").email("o@e.com").name("Owner").build();
        owner.setId(1L);
        viewer = User.builder().username("viewer").email("v@e.com").name("Viewer").build();
        viewer.setId(2L);

        story = Story.builder()
                .user(owner)
                .mediaUrl("https://cdn/x.png")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        story.setId(10L);
        story.setUuid(UUID.fromString(STORY_UUID));
    }

    @Nested
    @DisplayName("viewStory")
    class ViewStory {

        @Test
        void shouldNotRecordAViewWhenTheOwnerOpensTheirOwnStory() {
            when(storyRepository.findByUuid(UUID.fromString(STORY_UUID))).thenReturn(Optional.of(story));

            storyService.viewStory(STORY_UUID, owner);

            // Self-view must not even hit the existence check, and never persists a row.
            verify(storyViewRepository, never()).existsByStoryAndUser(any(), any());
            verify(storyViewRepository, never()).save(any());
        }

        @Test
        void shouldNotRecordADuplicateViewForTheSameViewer() {
            when(storyRepository.findByUuid(UUID.fromString(STORY_UUID))).thenReturn(Optional.of(story));
            when(storyViewRepository.existsByStoryAndUser(story, viewer)).thenReturn(true);

            storyService.viewStory(STORY_UUID, viewer);

            verify(storyViewRepository, never()).save(any());
        }

        @Test
        void shouldPersistAViewForANewViewer() {
            when(storyRepository.findByUuid(UUID.fromString(STORY_UUID))).thenReturn(Optional.of(story));
            when(storyViewRepository.existsByStoryAndUser(story, viewer)).thenReturn(false);

            storyService.viewStory(STORY_UUID, viewer);

            ArgumentCaptor<StoryView> saved = ArgumentCaptor.forClass(StoryView.class);
            verify(storyViewRepository).save(saved.capture());
            assertThat(saved.getValue().getStory()).isEqualTo(story);
            assertThat(saved.getValue().getUser()).isEqualTo(viewer);
            assertThat(saved.getValue().getViewedAt()).isNotNull();
        }

        @Test
        void shouldThrowNotFoundWhenStoryMissing() {
            when(storyRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storyService.viewStory(STORY_UUID, viewer))
                    .isInstanceOf(NotFoundException.class);
            verify(storyViewRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getStoryViewers")
    class GetStoryViewers {

        @Test
        void shouldReturnMappedViewersForTheOwnerNewestFirst() {
            StoryView view = StoryView.builder()
                    .story(story).user(viewer).viewedAt(Instant.parse("2026-07-25T10:15:30Z")).build();
            when(storyRepository.findByUuid(UUID.fromString(STORY_UUID))).thenReturn(Optional.of(story));
            when(storyViewRepository.findByStoryOrderByViewedAtDesc(story)).thenReturn(List.of(view));
            when(userMapper.toAuthUserResponse(viewer))
                    .thenReturn(AuthUserResponse.builder().id("2").username("viewer").build());

            List<StoryViewerResponse> result = storyService.getStoryViewers(STORY_UUID, owner);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUser().getUsername()).isEqualTo("viewer");
            assertThat(result.get(0).getViewedAt()).isEqualTo("2026-07-25T10:15:30Z");
        }

        @Test
        void shouldForbidInspectingAnotherUsersStoryViewers() {
            when(storyRepository.findByUuid(UUID.fromString(STORY_UUID))).thenReturn(Optional.of(story));

            assertThatThrownBy(() -> storyService.getStoryViewers(STORY_UUID, viewer))
                    .isInstanceOf(ForbiddenException.class);
            verify(storyViewRepository, never()).findByStoryOrderByViewedAtDesc(any());
        }
    }
}
