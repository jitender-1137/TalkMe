package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.AudioTrackDto;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.enums.PostAudience;
import com.chat.talkMe.enums.StoryKind;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.FeatureLockedException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.moderation.ModerationResult;
import com.chat.talkMe.repository.StoryRepository;
import com.chat.talkMe.repository.StoryViewRepository;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.FeatureAccessService;
import com.chat.talkMe.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StoryServiceImpl} covering <b>all public methods except</b> the story
 * view-tracking path ({@code viewStory}/{@code getStoryViewers}), which is exhaustively covered
 * by {@code com.chat.talkMe.service.StoryServiceImplViewTest}. Here we exercise create (visual +
 * voice + photo-music mux + moderation/feature/validation guards), active-feed audience filtering,
 * delete ownership, and my-stories mapping. Every collaborator is mocked; the SUT is built by hand.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoryServiceImpl — create / feed / delete / archive (unit)")
class StoryServiceImplTest {

    private static final UUID STORY_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String STORY_UUID_STR = STORY_UUID.toString();

    @Mock private StoryRepository storyRepository;
    @Mock private StoryViewRepository storyViewRepository;
    @Mock private UserMapper userMapper;
    @Mock private ContentModerationService moderationService;
    @Mock private UserSettingRepository userSettingRepository;
    @Mock private PhotoMusicMuxer photoMusicMuxer;
    @Mock private UserFollowRepository userFollowRepository;
    @Mock private NotificationService notificationService;
    @Mock private FeatureAccessService featureAccessService;

    private StoryServiceImpl service;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        service = new StoryServiceImpl(storyRepository, storyViewRepository, userMapper,
                moderationService, userSettingRepository, photoMusicMuxer, userFollowRepository,
                notificationService, featureAccessService);

        owner = User.builder().username("owner").name("Owner").email("o@e.com").build();
        owner.setId(1L);
        other = User.builder().username("other").name("Other").email("x@e.com").build();
        other.setId(2L);

        // Shared, harmless defaults for the private mapToStoryResponse mapper.
        lenient().when(storyViewRepository.existsByStoryAndUser(any(), any())).thenReturn(false);
        lenient().when(storyViewRepository.countByStory(any())).thenReturn(0L);
        lenient().when(userMapper.toAuthUserResponse(any()))
                .thenAnswer(inv -> AuthUserResponse.builder().username("mapped").build());
        lenient().when(userSettingRepository.findByUser(any())).thenReturn(Optional.empty());
    }

    private Story story(PostAudience audience, StoryKind kind, User user) {
        Story s = Story.builder()
                .user(user)
                .mediaUrl("https://cdn/x.png")
                .caption("hi")
                .audience(audience)
                .kind(kind)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        s.setId(10L);
        s.setUuid(STORY_UUID);
        s.setCreatedAt(Instant.now());
        return s;
    }

    /** Make {@code save} return a persisted-looking Story (uuid + createdAt populated). */
    private void stubSaveEchoesWithUuid() {
        when(storyRepository.save(any(Story.class))).thenAnswer(inv -> {
            Story s = inv.getArgument(0);
            s.setUuid(STORY_UUID);
            s.setCreatedAt(Instant.now());
            return s;
        });
    }

    @Nested
    @DisplayName("createStory")
    class CreateStory {

        private StoryRequest visualRequest() {
            StoryRequest r = new StoryRequest();
            r.setMediaUrl("https://cdn/pic.png");
            r.setCaption("nice sunset");
            return r;
        }

        @Test
        @DisplayName("nominal visual story → persists EVERYONE/VISUAL, fans out notification, returns response")
        void nominalVisual() {
            StoryRequest r = visualRequest();
            when(moderationService.moderateText("nice sunset")).thenReturn(ModerationResult.clean());
            stubSaveEchoesWithUuid();

            StoryResponse resp = service.createStory(r, owner);

            ArgumentCaptor<Story> saved = ArgumentCaptor.forClass(Story.class);
            verify(storyRepository).save(saved.capture());
            assertThat(saved.getValue().getAudience()).isEqualTo(PostAudience.EVERYONE);
            assertThat(saved.getValue().getKind()).isEqualTo(StoryKind.VISUAL);
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("https://cdn/pic.png");
            assertThat(saved.getValue().getUser()).isEqualTo(owner);
            assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());
            verify(notificationService).notifyFollowersAndFollowing(eq(owner), anyString(), anyString(),
                    eq("STORY"), eq(STORY_UUID_STR), anyString());
            assertThat(resp.getKind()).isEqualTo("VISUAL");
            assertThat(resp.getAudience()).isEqualTo("EVERYONE");
        }

        @Test
        @DisplayName("audience 'FRIENDS' → persists FRIENDS visibility")
        void friendsAudience() {
            StoryRequest r = visualRequest();
            r.setAudience("friends");
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            ArgumentCaptor<Story> saved = ArgumentCaptor.forClass(Story.class);
            verify(storyRepository).save(saved.capture());
            assertThat(saved.getValue().getAudience()).isEqualTo(PostAudience.FRIENDS);
        }

        @Test
        @DisplayName("photo + music (non-video) → muxes into a video and stores the muxed url")
        void photoMusicMux() {
            StoryRequest r = visualRequest();
            r.setAudio(AudioTrackDto.builder().audioUrl("https://cdn/song.mp3")
                    .audioStartSec(5).audioClipSeconds(20).build());
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(photoMusicMuxer.muxPhotoWithMusic("https://cdn/pic.png", "https://cdn/song.mp3", 5, 20))
                    .thenReturn("https://cdn/merged.mp4");
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            ArgumentCaptor<Story> saved = ArgumentCaptor.forClass(Story.class);
            verify(storyRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("https://cdn/merged.mp4");
        }

        @Test
        @DisplayName("photo + music but muxing unavailable (null) → falls back to the plain image")
        void muxFallback() {
            StoryRequest r = visualRequest();
            r.setAudio(AudioTrackDto.builder().audioUrl("https://cdn/song.mp3").build());
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(photoMusicMuxer.muxPhotoWithMusic(eq("https://cdn/pic.png"), eq("https://cdn/song.mp3"), anyInt(), anyInt()))
                    .thenReturn(null);
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            ArgumentCaptor<Story> saved = ArgumentCaptor.forClass(Story.class);
            verify(storyRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("https://cdn/pic.png");
        }

        @Test
        @DisplayName("media already an mp4 → muxing is skipped")
        void alreadyVideoSkipsMux() {
            StoryRequest r = new StoryRequest();
            r.setMediaUrl("https://cdn/clip.mp4");
            r.setCaption("clip");
            r.setAudio(AudioTrackDto.builder().audioUrl("https://cdn/song.mp3").build());
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            verify(photoMusicMuxer, never()).muxPhotoWithMusic(anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("audio present but no audioUrl → no muxing attempted")
        void audioWithoutUrlSkipsMux() {
            StoryRequest r = visualRequest();
            r.setAudio(AudioTrackDto.builder().audioTitle("no url").build());
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            verify(photoMusicMuxer, never()).muxPhotoWithMusic(anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("voice status with access + valid audio clip → persists VOICE, no mux")
        void voiceStatus() {
            StoryRequest r = new StoryRequest();
            r.setKind("voice");
            r.setMediaUrl("https://cdn/note.m4a");
            r.setCaption("hey");
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(featureAccessService.hasAccess(owner, FeatureKey.VOICE_STATUS)).thenReturn(true);
            stubSaveEchoesWithUuid();

            service.createStory(r, owner);

            ArgumentCaptor<Story> saved = ArgumentCaptor.forClass(Story.class);
            verify(storyRepository).save(saved.capture());
            assertThat(saved.getValue().getKind()).isEqualTo(StoryKind.VOICE);
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("https://cdn/note.m4a");
            verify(photoMusicMuxer, never()).muxPhotoWithMusic(anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("new-story notification failure is swallowed — creation still returns")
        void notifyFailureSwallowed() {
            StoryRequest r = visualRequest();
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            stubSaveEchoesWithUuid();
            Mockito.doThrow(new RuntimeException("fanout down"))
                    .when(notificationService).notifyFollowersAndFollowing(any(), anyString(), anyString(),
                            anyString(), anyString(), any());

            StoryResponse resp = service.createStory(r, owner);

            assertThat(resp).isNotNull();
            verify(storyRepository).save(any(Story.class));
        }

        @Test
        @DisplayName("explicit caption → ContentModerationException TM_490, nothing persisted")
        void explicitCaption() {
            StoryRequest r = visualRequest();
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.PROFANITY, 0.99, List.of("x")));

            assertThatThrownBy(() -> service.createStory(r, owner))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(storyRepository, never()).save(any());
        }

        @Test
        @DisplayName("voice status without entitlement → FeatureLockedException")
        void voiceLocked() {
            StoryRequest r = new StoryRequest();
            r.setKind("VOICE");
            r.setMediaUrl("https://cdn/note.m4a");
            r.setCaption("hey");
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(featureAccessService.hasAccess(owner, FeatureKey.VOICE_STATUS)).thenReturn(false);

            assertThatThrownBy(() -> service.createStory(r, owner))
                    .isInstanceOfSatisfying(FeatureLockedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo(FeatureLockedException.CODE));
            verify(storyRepository, never()).save(any());
        }

        @Test
        @DisplayName("voice status whose media is not an audio clip → BadRequest TM_232")
        void voiceNonAudio() {
            StoryRequest r = new StoryRequest();
            r.setKind("VOICE");
            r.setMediaUrl("https://cdn/pic.png");
            r.setCaption("hey");
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(featureAccessService.hasAccess(owner, FeatureKey.VOICE_STATUS)).thenReturn(true);

            assertThatThrownBy(() -> service.createStory(r, owner))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_232"));
            verify(storyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getActiveStories")
    class GetActiveStories {

        @Test
        @DisplayName("EVERYONE stories are visible to any viewer")
        void everyoneVisible() {
            Story s = story(PostAudience.EVERYONE, StoryKind.VISUAL, owner);
            when(storyRepository.findActiveStories(any())).thenReturn(List.of(s));

            List<StoryResponse> result = service.getActiveStories(other);

            assertThat(result).hasSize(1);
            verify(userFollowRepository, never())
                    .existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(any(), any(), any());
        }

        @Test
        @DisplayName("FRIENDS story is visible to its owner")
        void friendsVisibleToOwner() {
            Story s = story(PostAudience.FRIENDS, StoryKind.VISUAL, owner);
            when(storyRepository.findActiveStories(any())).thenReturn(List.of(s));

            assertThat(service.getActiveStories(owner)).hasSize(1);
        }

        @Test
        @DisplayName("FRIENDS story is visible to an accepted follower (either direction)")
        void friendsVisibleToFollower() {
            Story s = story(PostAudience.FRIENDS, StoryKind.VISUAL, owner);
            when(storyRepository.findActiveStories(any())).thenReturn(List.of(s));
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(other, owner, "ACCEPTED"))
                    .thenReturn(true);

            assertThat(service.getActiveStories(other)).hasSize(1);
        }

        @Test
        @DisplayName("FRIENDS story is hidden from a non-friend viewer")
        void friendsHiddenFromStranger() {
            Story s = story(PostAudience.FRIENDS, StoryKind.VISUAL, owner);
            when(storyRepository.findActiveStories(any())).thenReturn(List.of(s));
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(any(), any(), eq("ACCEPTED")))
                    .thenReturn(false);

            assertThat(service.getActiveStories(other)).isEmpty();
        }

        @Test
        @DisplayName("FRIENDS story is hidden from an anonymous (null) viewer")
        void friendsHiddenFromAnonymous() {
            Story s = story(PostAudience.FRIENDS, StoryKind.VISUAL, owner);
            when(storyRepository.findActiveStories(any())).thenReturn(List.of(s));

            assertThat(service.getActiveStories(null)).isEmpty();
        }

        @Test
        @DisplayName("no active stories → empty list")
        void empty() {
            when(storyRepository.findActiveStories(any())).thenReturn(List.of());
            assertThat(service.getActiveStories(owner)).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteStory")
    class DeleteStory {

        @Test
        @DisplayName("owner deletes → soft-deletes and saves")
        void ownerDeletes() {
            Story s = story(PostAudience.EVERYONE, StoryKind.VISUAL, owner);
            when(storyRepository.findByUuid(STORY_UUID)).thenReturn(Optional.of(s));

            service.deleteStory(STORY_UUID_STR, owner);

            assertThat(s.isDeleted()).isTrue();
            verify(storyRepository).save(s);
        }

        @Test
        @DisplayName("unknown story → NotFound TM_231")
        void notFound() {
            when(storyRepository.findByUuid(STORY_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteStory(STORY_UUID_STR, owner))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_231"));
            verify(storyRepository, never()).save(any());
        }

        @Test
        @DisplayName("deleting another user's story → Forbidden TM_103")
        void notOwner() {
            Story s = story(PostAudience.EVERYONE, StoryKind.VISUAL, owner);
            when(storyRepository.findByUuid(STORY_UUID)).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> service.deleteStory(STORY_UUID_STR, other))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(storyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getMyStories")
    class GetMyStories {

        @Test
        @DisplayName("maps all of the user's stories, marking them as owned")
        void mapsMine() {
            Story s = story(PostAudience.EVERYONE, StoryKind.VISUAL, owner);
            when(storyRepository.findAllByUser(owner)).thenReturn(List.of(s));

            List<StoryResponse> result = service.getMyStories(owner);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isOwner()).isTrue();
            assertThat(result.get(0).getId()).isEqualTo(STORY_UUID_STR);
        }

        @Test
        @DisplayName("owner story with FRIENDS_ONLY messaging setting → response flags it on the mapped user")
        void mapsMessagingPrivacy() {
            Story s = story(PostAudience.EVERYONE, StoryKind.VISUAL, owner);
            when(storyRepository.findAllByUser(owner)).thenReturn(List.of(s));
            UserSetting setting = new UserSetting();
            setting.setMessagingPrivacy(MessagingPrivacy.FRIENDS_ONLY);
            when(userSettingRepository.findByUser(owner)).thenReturn(Optional.of(setting));

            List<StoryResponse> result = service.getMyStories(owner);

            assertThat(result.get(0).getUser().getMessagingFriendsOnly()).isTrue();
        }

        @Test
        @DisplayName("no stories → empty list")
        void empty() {
            when(storyRepository.findAllByUser(owner)).thenReturn(List.of());
            assertThat(service.getMyStories(owner)).isEmpty();
        }
    }
}
