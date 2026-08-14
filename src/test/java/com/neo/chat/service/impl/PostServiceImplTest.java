package com.neo.chat.service.impl;

import com.neo.chat.domain.BaseEntity;
import com.neo.chat.domain.Poll;
import com.neo.chat.domain.PollOption;
import com.neo.chat.domain.PollVote;
import com.neo.chat.domain.Post;
import com.neo.chat.domain.PostBookmark;
import com.neo.chat.domain.PostComment;
import com.neo.chat.domain.PostCommentLike;
import com.neo.chat.domain.PostLike;
import com.neo.chat.domain.PostMedia;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.request.PollRequest;
import com.neo.chat.dto.request.PostCommentRequest;
import com.neo.chat.dto.request.PostMediaRequest;
import com.neo.chat.dto.request.PostRequest;
import com.neo.chat.dto.response.AudioTrackDto;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.PostCommentResponse;
import com.neo.chat.dto.response.PostResponse;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.enums.MessageType;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.PostAudience;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.FeatureLockedException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.PollOptionRepository;
import com.neo.chat.repository.PollRepository;
import com.neo.chat.repository.PollVoteRepository;
import com.neo.chat.repository.PostBookmarkRepository;
import com.neo.chat.repository.PostCommentLikeRepository;
import com.neo.chat.repository.PostCommentRepository;
import com.neo.chat.repository.PostLikeRepository;
import com.neo.chat.repository.PostMediaRepository;
import com.neo.chat.repository.PostRepository;
import com.neo.chat.repository.UserFollowRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.FeatureAccessService;
import com.neo.chat.service.NotificationService;
import com.neo.chat.storage.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link PostServiceImpl} — the Instagram-style post/feed engine
 * (create/edit/delete posts, feed + profile feed, likes, comments + replies, comment likes,
 * bookmarks, polls + votes, temporary-post TTL, photo+music mux, audience gating, and the
 * expiry reaper).
 *
 * <p>Every public method is enumerated over its positive branches (nominal, each flag, side
 * effects, idempotency) and negative branches (not-found, ownership, validation, moderation,
 * feature-locked). Error paths assert the exact {@code TM_###} domain code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostServiceImpl (unit)")
class PostServiceImplTest {

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostMediaRepository postMediaRepository;
    @Mock
    private PostLikeRepository postLikeRepository;
    @Mock
    private PostCommentRepository postCommentRepository;
    @Mock
    private PostCommentLikeRepository postCommentLikeRepository;
    @Mock
    private PostBookmarkRepository postBookmarkRepository;
    @Mock
    private PollRepository pollRepository;
    @Mock
    private PollOptionRepository pollOptionRepository;
    @Mock
    private PollVoteRepository pollVoteRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private PhotoMusicMuxer photoMusicMuxer;
    @Mock
    private MediaStorage mediaStorage;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private FeatureAccessService featureAccessService;

    private PostServiceImpl service;

    private User currentUser;
    private User owner;

    private final Pageable pageable = PageRequest.of(0, 10);

    @BeforeEach
    void setUp() {
        service = new PostServiceImpl(
                postRepository, postMediaRepository, postLikeRepository, postCommentRepository,
                postCommentLikeRepository, postBookmarkRepository, pollRepository, pollOptionRepository,
                pollVoteRepository, userRepository, userSettingRepository, userMapper, notificationService,
                moderationService, photoMusicMuxer, mediaStorage, userFollowRepository, featureAccessService);

        currentUser = user(1L, "alice");
        owner = user(2L, "bob");

        // Shared defaults — moderation passes, saves assign identity/timestamps, mapper is non-null.
        lenient().when(moderationService.moderateText(any())).thenReturn(ModerationResult.clean());
        lenient().when(postRepository.save(any(Post.class))).thenAnswer(assignIdentity());
        lenient().when(postMediaRepository.save(any(PostMedia.class))).thenAnswer(assignIdentity());
        lenient().when(pollRepository.save(any(Poll.class))).thenAnswer(assignIdentity());
        lenient().when(pollOptionRepository.save(any(PollOption.class))).thenAnswer(assignIdentity());
        lenient().when(postCommentRepository.save(any(PostComment.class))).thenAnswer(assignIdentity());
        lenient().when(postLikeRepository.save(any(PostLike.class))).thenAnswer(assignIdentity());
        lenient().when(postCommentLikeRepository.save(any(PostCommentLike.class))).thenAnswer(assignIdentity());
        lenient().when(postBookmarkRepository.save(any(PostBookmark.class))).thenAnswer(assignIdentity());
        lenient().when(pollVoteRepository.save(any(PollVote.class))).thenAnswer(assignIdentity());
        lenient().when(userMapper.toAuthUserResponse(any())).thenReturn(AuthUserResponse.builder().build());
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static <T> Answer<T> assignIdentity() {
        return inv -> {
            T arg = inv.getArgument(0);
            if (arg instanceof BaseEntity be) {
                if (be.getUuid() == null) be.setUuid(UUID.randomUUID());
                if (be.getCreatedAt() == null) be.setCreatedAt(Instant.now());
            }
            return arg;
        };
    }

    private User user(long id, String username) {
        User u = User.builder().username(username).name("Name-" + id).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private Post post(long id, User author) {
        Post p = Post.builder().user(author).audience(PostAudience.EVERYONE).build();
        p.setId(id);
        p.setUuid(UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        return p;
    }

    private Poll pollOn(long id, Post post, String question) {
        Poll pl = Poll.builder().post(post).question(question).build();
        pl.setId(id);
        pl.setUuid(UUID.randomUUID());
        return pl;
    }

    private PollOption option(long id, Poll poll, String text) {
        PollOption o = PollOption.builder().poll(poll).text(text).build();
        o.setId(id);
        o.setUuid(UUID.randomUUID());
        return o;
    }

    private PostComment comment(long id, User author, Post post, String content, PostComment parent) {
        PostComment c = PostComment.builder().post(post).user(author).content(content).parent(parent).build();
        c.setId(id);
        c.setUuid(UUID.randomUUID());
        c.setCreatedAt(Instant.now());
        return c;
    }

    private ModerationResult explicit() {
        return ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.99, List.of());
    }

    private PostRequest textRequest(String content) {
        PostRequest r = new PostRequest();
        r.setContent(content);
        return r;
    }

    // ─────────────────────────── createPost ───────────────────────────

    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("text-only post → saved, followers notified, response returned")
        void textOnly() {
            PostResponse res = service.createPost(textRequest("hello world"), currentUser);

            assertThat(res).isNotNull();
            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            assertThat(saved.getValue().getContent()).isEqualTo("hello world");
            assertThat(saved.getValue().getAudience()).isEqualTo(PostAudience.EVERYONE);
            assertThat(saved.getValue().getExpiresAt()).isNull();
            verify(notificationService).notifyFollowersAndFollowing(
                    eq(currentUser), any(), any(), eq("POST"), any(), any());
        }

        @Test
        @DisplayName("no text, no media, no poll → BadRequestException TM_230")
        void emptyPost() {
            assertThatThrownBy(() -> service.createPost(new PostRequest(), currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_230"));
            verify(postRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit content caption → ContentModerationException TM_490")
        void explicitContent() {
            when(moderationService.moderateText("bad")).thenReturn(explicit());

            assertThatThrownBy(() -> service.createPost(textRequest("bad"), currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(postRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit separate caption field → ContentModerationException TM_490")
        void explicitCaption() {
            when(moderationService.moderateText("nasty")).thenReturn(explicit());
            PostRequest r = textRequest("clean body");
            r.setCaption("nasty");

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
        }

        @Test
        @DisplayName("audience 'FRIENDS' (case-insensitive) → post stored as FRIENDS")
        void friendsAudience() {
            PostRequest r = textRequest("secret");
            r.setAudience(" friends ");

            service.createPost(r, currentUser);

            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            assertThat(saved.getValue().getAudience()).isEqualTo(PostAudience.FRIENDS);
        }

        @Test
        @DisplayName("temporary post with feature access → expiresAt set in the future")
        void temporaryPostWithAccess() {
            when(featureAccessService.hasAccess(currentUser, FeatureKey.TEMPORARY_POSTS)).thenReturn(true);
            PostRequest r = textRequest("ephemeral");
            r.setExpiresInSeconds(600);

            service.createPost(r, currentUser);

            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            assertThat(saved.getValue().getExpiresAt()).isNotNull();
            assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("temporary post below min TTL is clamped up to the 5-minute floor")
        void temporaryPostClampedToFloor() {
            when(featureAccessService.hasAccess(currentUser, FeatureKey.TEMPORARY_POSTS)).thenReturn(true);
            PostRequest r = textRequest("ephemeral");
            r.setExpiresInSeconds(1);

            service.createPost(r, currentUser);

            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            // Clamp floor = 300s → expiry must be well beyond a tiny 1s TTL.
            assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now().plusSeconds(290));
        }

        @Test
        @DisplayName("temporary post without feature access → FeatureLockedException TM_FEATURE_LOCKED")
        void temporaryPostLocked() {
            when(featureAccessService.hasAccess(currentUser, FeatureKey.TEMPORARY_POSTS)).thenReturn(false);
            PostRequest r = textRequest("ephemeral");
            r.setExpiresInSeconds(600);

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(FeatureLockedException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_FEATURE_LOCKED"));
            verify(postRepository, never()).save(any());
        }

        @Test
        @DisplayName("media post with unresolved local copy → media saved, moderation skipped")
        void mediaSavedNoLocalCopy() {
            PostRequest r = new PostRequest();
            r.setMedia(List.of(new PostMediaRequest("http://img/1.jpg", "IMAGE",
                    null, null, null, null, null)));
            // mediaStorage.localCopy default → Optional.empty() → moderation skipped.

            service.createPost(r, currentUser);

            ArgumentCaptor<PostMedia> saved = ArgumentCaptor.forClass(PostMedia.class);
            verify(postMediaRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("http://img/1.jpg");
            assertThat(saved.getValue().getOrderIndex()).isZero();
            verify(moderationService, never()).moderateMedia(any(), any());
        }

        @Test
        @DisplayName("media post with a resolvable file that is NSFW → ContentModerationException TM_490")
        void mediaModerationBlocks() {
            PostRequest r = new PostRequest();
            r.setMedia(List.of(new PostMediaRequest("http://img/x.jpg", "IMAGE",
                    null, null, null, null, null)));
            MediaStorage.LocalFile local = mock(MediaStorage.LocalFile.class);
            when(local.path()).thenReturn(Path.of("/tmp/x.jpg"));
            when(mediaStorage.localCopy("http://img/x.jpg")).thenReturn(Optional.of(local));
            when(moderationService.moderateMedia(Path.of("/tmp/x.jpg"), MessageType.IMAGE)).thenReturn(explicit());

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(postMediaRepository, never()).save(any());
        }

        /**
         * A lone still image plus a soundtrack triggers the muxer; the resulting MP4 replaces the
         * image so the stored media flips from IMAGE to VIDEO.
         */
        @Test
        @DisplayName("single image + soundtrack → muxed into a VIDEO media item")
        void photoMusicMux() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://img/still.jpg", "IMAGE", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().audioUrl("http://audio/song.mp3")
                    .audioStartSec(3).audioClipSeconds(20).build());
            when(photoMusicMuxer.muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 3, 20))
                    .thenReturn("http://media/muxed.mp4");

            service.createPost(r, currentUser);

            verify(photoMusicMuxer).muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 3, 20);
            ArgumentCaptor<PostMedia> saved = ArgumentCaptor.forClass(PostMedia.class);
            verify(postMediaRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaUrl()).isEqualTo("http://media/muxed.mp4");
            assertThat(saved.getValue().getMediaType()).isEqualTo("VIDEO");
        }

        @Test
        @DisplayName("photo+music source image is NSFW → ContentModerationException TM_490, no mux")
        void photoMusicSourceBlocked() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://img/nsfw.jpg", "IMAGE", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().audioUrl("http://audio/song.mp3").build());
            MediaStorage.LocalFile local = mock(MediaStorage.LocalFile.class);
            when(local.path()).thenReturn(Path.of("/tmp/nsfw.jpg"));
            when(mediaStorage.localCopy("http://img/nsfw.jpg")).thenReturn(Optional.of(local));
            when(moderationService.moderateMedia(Path.of("/tmp/nsfw.jpg"), MessageType.IMAGE)).thenReturn(explicit());

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(photoMusicMuxer, never()).muxPhotoWithMusic(any(), any(), ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("poll post with 2+ options → poll + options persisted, response carries the poll")
        void pollPost() {
            PostRequest r = new PostRequest();
            r.setPoll(new PollRequest("Best color?", List.of("Red", "Blue")));

            PostResponse res = service.createPost(r, currentUser);

            assertThat(res.getPoll()).isNotNull();
            verify(pollRepository).save(any(Poll.class));
            verify(pollOptionRepository, times(2)).save(any(PollOption.class));
        }

        @Test
        @DisplayName("poll with fewer than 2 valid options → BadRequestException TM_225")
        void pollTooFewOptions() {
            PostRequest r = new PostRequest();
            r.setPoll(new PollRequest("One?", Arrays.asList("Only", " ", null)));

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_225"));
            verify(pollRepository, never()).save(any());
        }

        @Test
        @DisplayName("poll question is explicit → ContentModerationException TM_490")
        void pollExplicitQuestion() {
            when(moderationService.moderateText("dirty?")).thenReturn(explicit());
            PostRequest r = new PostRequest();
            r.setPoll(new PollRequest("dirty?", List.of("A", "B")));

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(pollRepository, never()).save(any());
        }

        @Test
        @DisplayName("soundtrack present → AudioTrack stored on the post")
        void audioTrackStored() {
            PostRequest r = textRequest("with music");
            r.setAudio(AudioTrackDto.builder().audioUrl("http://audio/track.mp3").build());

            PostResponse res = service.createPost(r, currentUser);

            assertThat(res).isNotNull();
            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository, Mockito.atLeastOnce()).save(saved.capture());
            assertThat(saved.getValue().getAudio()).isNotNull();
            assertThat(saved.getValue().getAudio().getAudioUrl()).isEqualTo("http://audio/track.mp3");
        }

        @Test
        @DisplayName("notification fan-out failure is swallowed — post still created")
        void notificationFailureSwallowed() {
            doThrow(new RuntimeException("notify down")).when(notificationService)
                    .notifyFollowersAndFollowing(any(), any(), any(), any(), any(), any());

            PostResponse res = service.createPost(textRequest("resilient"), currentUser);

            assertThat(res).isNotNull();
            verify(postRepository).save(any(Post.class));
        }
    }

    // ─────────────────────────── votePoll ───────────────────────────

    @Nested
    @DisplayName("votePoll")
    class VotePoll {

        private Post pollPost;
        private Poll poll;
        private PollOption optA;
        private PollOption optB;

        @BeforeEach
        void seed() {
            pollPost = post(10L, owner);
            poll = pollOn(20L, pollPost, "Q?");
            optA = option(30L, poll, "A");
            optB = option(31L, poll, "B");
            poll.getOptions().add(optA);
            poll.getOptions().add(optB);
            pollPost.setPoll(poll);
        }

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void postNotFound() {
            UUID pid = UUID.randomUUID();
            when(postRepository.findByUuid(pid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.votePoll(pid.toString(), UUID.randomUUID().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("post is not a poll → BadRequestException TM_226")
        void notAPoll() {
            Post plain = post(11L, owner);
            when(postRepository.findByUuid(plain.getUuid())).thenReturn(Optional.of(plain));

            assertThatThrownBy(() -> service.votePoll(plain.getUuid().toString(),
                    UUID.randomUUID().toString(), currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_226"));
        }

        @Test
        @DisplayName("poll option not found → NotFoundException TM_227")
        void optionNotFound() {
            when(postRepository.findByUuid(pollPost.getUuid())).thenReturn(Optional.of(pollPost));
            UUID oid = UUID.randomUUID();
            when(pollOptionRepository.findByUuid(oid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.votePoll(pollPost.getUuid().toString(), oid.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_227"));
        }

        @Test
        @DisplayName("option belongs to another poll → BadRequestException TM_228")
        void optionWrongPoll() {
            when(postRepository.findByUuid(pollPost.getUuid())).thenReturn(Optional.of(pollPost));
            Poll otherPoll = pollOn(99L, post(12L, owner), "Other?");
            PollOption alien = option(40L, otherPoll, "X");
            when(pollOptionRepository.findByUuid(alien.getUuid())).thenReturn(Optional.of(alien));

            assertThatThrownBy(() -> service.votePoll(pollPost.getUuid().toString(),
                    alien.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_228"));
        }

        @Test
        @DisplayName("first vote by a non-owner → vote saved and poll owner notified")
        void firstVoteNotifiesOwner() {
            when(postRepository.findByUuid(pollPost.getUuid())).thenReturn(Optional.of(pollPost));
            when(pollOptionRepository.findByUuid(optA.getUuid())).thenReturn(Optional.of(optA));
            when(pollVoteRepository.findByPollAndUser(poll, currentUser)).thenReturn(Optional.empty());

            service.votePoll(pollPost.getUuid().toString(), optA.getUuid().toString(), currentUser);

            ArgumentCaptor<PollVote> saved = ArgumentCaptor.forClass(PollVote.class);
            verify(pollVoteRepository).save(saved.capture());
            assertThat(saved.getValue().getOption()).isEqualTo(optA);
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("POLL"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("first vote by the poll owner (self) → no self-notification")
        void firstVoteSelfNoNotify() {
            Post ownPoll = post(13L, currentUser);
            Poll pl = pollOn(21L, ownPoll, "Mine?");
            PollOption opt = option(50L, pl, "A");
            pl.getOptions().add(opt);
            ownPoll.setPoll(pl);
            when(postRepository.findByUuid(ownPoll.getUuid())).thenReturn(Optional.of(ownPoll));
            when(pollOptionRepository.findByUuid(opt.getUuid())).thenReturn(Optional.of(opt));
            when(pollVoteRepository.findByPollAndUser(pl, currentUser)).thenReturn(Optional.empty());

            service.votePoll(ownPoll.getUuid().toString(), opt.getUuid().toString(), currentUser);

            verify(pollVoteRepository).save(any(PollVote.class));
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }

        /**
         * Voting again for the option the user already picked removes the vote entirely
         * (delete, not re-save) and fires no notification.
         */
        @Test
        @DisplayName("re-tapping the current choice retracts the vote (toggle-off, no notify)")
        void toggleOff() {
            when(postRepository.findByUuid(pollPost.getUuid())).thenReturn(Optional.of(pollPost));
            when(pollOptionRepository.findByUuid(optA.getUuid())).thenReturn(Optional.of(optA));
            PollVote existing = PollVote.builder().poll(poll).option(optA).user(currentUser).build();
            existing.setId(60L);
            when(pollVoteRepository.findByPollAndUser(poll, currentUser)).thenReturn(Optional.of(existing));

            service.votePoll(pollPost.getUuid().toString(), optA.getUuid().toString(), currentUser);

            verify(pollVoteRepository).delete(existing);
            verify(pollVoteRepository, never()).save(any());
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }

        /**
         * Picking a different option reuses the existing vote row (option re-pointed and saved),
         * never deletes it, and does not re-notify as a brand-new vote.
         */
        @Test
        @DisplayName("choosing a different option switches the existing vote (no new-vote notify)")
        void switchVote() {
            when(postRepository.findByUuid(pollPost.getUuid())).thenReturn(Optional.of(pollPost));
            when(pollOptionRepository.findByUuid(optB.getUuid())).thenReturn(Optional.of(optB));
            PollVote existing = PollVote.builder().poll(poll).option(optA).user(currentUser).build();
            existing.setId(61L);
            when(pollVoteRepository.findByPollAndUser(poll, currentUser)).thenReturn(Optional.of(existing));

            service.votePoll(pollPost.getUuid().toString(), optB.getUuid().toString(), currentUser);

            assertThat(existing.getOption()).isEqualTo(optB);
            verify(pollVoteRepository).save(existing);
            verify(pollVoteRepository, never()).delete(any());
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────── getPost / getPostByShortCode ───────────────────────────

    @Nested
    @DisplayName("getPost")
    class GetPost {

        @Test
        @DisplayName("public post → returned")
        void publicPost() {
            Post p = post(70L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            PostResponse res = service.getPost(p.getUuid().toString(), currentUser);

            assertThat(res).isNotNull();
        }

        @Test
        @DisplayName("missing post → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("soft-deleted post → NotFoundException TM_211")
        void deleted() {
            Post p = post(71L, owner);
            p.setDeleted(true);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPost(p.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("expired temporary post → NotFoundException TM_211")
        void expired() {
            Post p = post(72L, owner);
            p.setExpiresAt(Instant.now().minusSeconds(60));
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPost(p.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("FRIENDS post viewed by a stranger → NotFoundException TM_211 (hidden)")
        void friendsHiddenFromStranger() {
            Post p = post(73L, owner);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            // no accepted follow either direction → both existsBy... default false

            assertThatThrownBy(() -> service.getPost(p.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("FRIENDS post viewed by its author → returned")
        void friendsVisibleToAuthor() {
            Post p = post(74L, currentUser);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            assertThat(service.getPost(p.getUuid().toString(), currentUser)).isNotNull();
        }

        @Test
        @DisplayName("FRIENDS post viewed by an accepted friend → returned")
        void friendsVisibleToFriend() {
            Post p = post(75L, owner);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(
                    currentUser, owner, "ACCEPTED")).thenReturn(true);

            assertThat(service.getPost(p.getUuid().toString(), currentUser)).isNotNull();
        }
    }

    @Nested
    @DisplayName("getPostByShortCode")
    class GetPostByShortCode {

        @Test
        @DisplayName("public post by short code → returned")
        void success() {
            Post p = post(80L, owner);
            when(postRepository.findByShortCode("abc123")).thenReturn(Optional.of(p));

            assertThat(service.getPostByShortCode("abc123", currentUser)).isNotNull();
        }

        @Test
        @DisplayName("unknown short code → NotFoundException TM_211")
        void notFound() {
            when(postRepository.findByShortCode("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPostByShortCode("nope", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("deleted post by short code → NotFoundException TM_211")
        void deleted() {
            Post p = post(81L, owner);
            p.setDeleted(true);
            when(postRepository.findByShortCode("d1")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPostByShortCode("d1", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }
    }

    // ─────────────────────────── updatePost ───────────────────────────

    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePost(id.toString(), textRequest("x"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("editing another user's post → ForbiddenException TM_103")
        void notOwner() {
            Post p = post(90L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.updatePost(p.getUuid().toString(), textRequest("x"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(postRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit new content → ContentModerationException TM_490")
        void explicitContent() {
            Post p = post(91L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(moderationService.moderateText("filth")).thenReturn(explicit());

            assertThatThrownBy(() -> service.updatePost(p.getUuid().toString(), textRequest("filth"), currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
        }

        @Test
        @DisplayName("explicit new caption → ContentModerationException TM_490")
        void explicitCaption() {
            Post p = post(92L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(moderationService.moderateText("nastycap")).thenReturn(explicit());
            PostRequest r = new PostRequest();
            r.setCaption("nastycap");

            assertThatThrownBy(() -> service.updatePost(p.getUuid().toString(), r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
        }

        @Test
        @DisplayName("owner updates content + caption → persisted")
        void success() {
            Post p = post(93L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostRequest r = new PostRequest();
            r.setContent("edited body");
            r.setCaption("edited caption");

            service.updatePost(p.getUuid().toString(), r, currentUser);

            assertThat(p.getContent()).isEqualTo("edited body");
            assertThat(p.getCaption()).isEqualTo("edited caption");
            verify(postRepository).save(p);
        }

        @Test
        @DisplayName("blank caption is allowed (not moderated) and stored")
        void blankCaptionAllowed() {
            Post p = post(94L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostRequest r = new PostRequest();
            r.setCaption("");

            service.updatePost(p.getUuid().toString(), r, currentUser);

            assertThat(p.getCaption()).isEmpty();
            verify(postRepository).save(p);
        }
    }

    // ─────────────────────────── getFeed / getProfileFeed ───────────────────────────

    @Nested
    @DisplayName("getFeed")
    class GetFeed {

        @Test
        @DisplayName("visible posts are mapped to responses")
        void mapsVisible() {
            Post p = post(100L, owner);
            when(postRepository.findVisibleFeedFor(currentUser, pageable))
                    .thenReturn(new PageImpl<>(List.of(p)));

            Page<PostResponse> page = service.getFeed(pageable, currentUser);

            assertThat(page.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("empty feed → empty page, no NPE")
        void empty() {
            when(postRepository.findVisibleFeedFor(currentUser, pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThat(service.getFeed(pageable, currentUser).getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getProfileFeed")
    class GetProfileFeed {

        @Test
        @DisplayName("'me' resolves to the current user's own feed")
        void meAlias() {
            Post p = post(110L, currentUser);
            when(postRepository.findProfileFeedFor(currentUser, currentUser, pageable))
                    .thenReturn(new PageImpl<>(List.of(p)));

            Page<PostResponse> page = service.getProfileFeed("me", pageable, currentUser);

            assertThat(page.getContent()).hasSize(1);
            verify(userRepository, never()).findByUuid(any());
        }

        @Test
        @DisplayName("explicit user uuid → that user's profile feed")
        void byUuid() {
            when(userRepository.findByUuid(owner.getUuid())).thenReturn(Optional.of(owner));
            when(postRepository.findProfileFeedFor(owner, currentUser, pageable))
                    .thenReturn(new PageImpl<>(List.of(post(111L, owner))));

            assertThat(service.getProfileFeed(owner.getUuid().toString(), pageable, currentUser)
                    .getContent()).hasSize(1);
        }

        @Test
        @DisplayName("unknown user uuid → NotFoundException TM_064")
        void userNotFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getProfileFeed(id.toString(), pageable, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────── deletePost ───────────────────────────

    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("deleting another user's post → ForbiddenException TM_103")
        void notOwner() {
            Post p = post(120L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.deletePost(p.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(postRepository, never()).save(any());
        }

        @Test
        @DisplayName("owner deletes → soft-deleted and saved")
        void success() {
            Post p = post(121L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            service.deletePost(p.getUuid().toString(), currentUser);

            assertThat(p.isDeleted()).isTrue();
            verify(postRepository).save(p);
        }
    }

    // ─────────────────────────── reapExpiredPosts ───────────────────────────

    @Nested
    @DisplayName("reapExpiredPosts")
    class ReapExpiredPosts {

        @Test
        @DisplayName("nothing expired → returns 0, no saveAll")
        void noop() {
            Instant now = Instant.now();
            when(postRepository.findExpiredActive(eq(now), any(Pageable.class))).thenReturn(List.of());

            int reaped = service.reapExpiredPosts(now);

            assertThat(reaped).isZero();
            verify(postRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("expired batch → each soft-deleted, saveAll called, count returned")
        void reapsBatch() {
            Instant now = Instant.now();
            Post a = post(130L, owner);
            Post b = post(131L, owner);
            when(postRepository.findExpiredActive(eq(now), any(Pageable.class))).thenReturn(List.of(a, b));

            int reaped = service.reapExpiredPosts(now);

            assertThat(reaped).isEqualTo(2);
            assertThat(a.isDeleted()).isTrue();
            assertThat(b.isDeleted()).isTrue();
            verify(postRepository).saveAll(List.of(a, b));
        }
    }

    // ─────────────────────────── likePost / unlikePost ───────────────────────────

    @Nested
    @DisplayName("likePost")
    class LikePost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.likePost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("already liked → idempotent no-op (no save, no notify)")
        void alreadyLiked() {
            Post p = post(140L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(true);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(postLikeRepository, never()).save(any());
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("new like on someone else's post → saved and owner notified")
        void newLikeNotifiesOwner() {
            Post p = post(141L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(postLikeRepository).save(any(PostLike.class));
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("LIKE"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("liking own post → saved, no self-notification")
        void likeOwnPostNoNotify() {
            Post p = post(142L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(postLikeRepository).save(any(PostLike.class));
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("unlikePost")
    class UnlikePost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlikePost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("existing like → deleted")
        void deletesExisting() {
            Post p = post(150L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostLike like = PostLike.builder().post(p).user(currentUser).build();
            when(postLikeRepository.findByPostAndUser(p, currentUser)).thenReturn(Optional.of(like));

            service.unlikePost(p.getUuid().toString(), currentUser);

            verify(postLikeRepository).delete(like);
        }

        @Test
        @DisplayName("no existing like → no delete")
        void noExisting() {
            Post p = post(151L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.findByPostAndUser(p, currentUser)).thenReturn(Optional.empty());

            service.unlikePost(p.getUuid().toString(), currentUser);

            verify(postLikeRepository, never()).delete(any());
        }
    }

    // ─────────────────────────── getPostLikes ───────────────────────────

    @Nested
    @DisplayName("getPostLikes")
    class GetPostLikes {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPostLikes(id.toString(), pageable, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("likers mapped, friends-only flag reflects the liker's privacy setting")
        void mapsLikersWithFriendsOnlyFlag() {
            Post p = post(160L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            User liker = user(3L, "carol");
            PostLike like = PostLike.builder().post(p).user(liker).build();
            when(postLikeRepository.findByPost(p, pageable)).thenReturn(new PageImpl<>(List.of(like)));
            UserSetting setting = new UserSetting();
            setting.setMessagingPrivacy(MessagingPrivacy.FRIENDS_ONLY);
            when(userSettingRepository.findByUser(liker)).thenReturn(Optional.of(setting));

            Page<AuthUserResponse> page = service.getPostLikes(p.getUuid().toString(), pageable, currentUser);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getMessagingFriendsOnly()).isTrue();
        }
    }

    // ─────────────────────────── addComment ───────────────────────────

    @Nested
    @DisplayName("addComment")
    class AddComment {

        private PostCommentRequest req(String content, String parentId) {
            PostCommentRequest r = new PostCommentRequest();
            r.setContent(content);
            r.setParentId(parentId);
            return r;
        }

        @Test
        @DisplayName("explicit comment → ContentModerationException TM_490")
        void explicitComment() {
            when(moderationService.moderateText("swear")).thenReturn(explicit());

            assertThatThrownBy(() -> service.addComment(UUID.randomUUID().toString(),
                    req("swear", null), currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(postCommentRepository, never()).save(any());
        }

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void postNotFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addComment(id.toString(), req("nice", null), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("top-level comment on another's post → saved and post owner notified")
        void topLevelNotifiesOwner() {
            Post p = post(170L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            PostCommentResponse res = service.addComment(p.getUuid().toString(), req("great post", null), currentUser);

            assertThat(res).isNotNull();
            verify(postCommentRepository).save(any(PostComment.class));
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("commenting on own post → saved, no self-notification")
        void ownPostNoNotify() {
            Post p = post(171L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            service.addComment(p.getUuid().toString(), req("mine", null), currentUser);

            verify(postCommentRepository).save(any(PostComment.class));
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reply notifies both the post owner and the parent comment's author")
        void replyNotifiesOwnerAndParentAuthor() {
            Post p = post(172L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            User parentAuthor = user(3L, "carol");
            PostComment parent = comment(200L, parentAuthor, p, "parent", null);
            when(postCommentRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));

            service.addComment(p.getUuid().toString(), req("replying", parent.getUuid().toString()), currentUser);

            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
            verify(notificationService).createNotification(eq(parentAuthor), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("unknown parentId is treated as a top-level comment")
        void unknownParentTreatedTopLevel() {
            Post p = post(173L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            UUID missing = UUID.randomUUID();
            when(postCommentRepository.findByUuid(missing)).thenReturn(Optional.empty());

            service.addComment(p.getUuid().toString(), req("hi", missing.toString()), currentUser);

            ArgumentCaptor<PostComment> saved = ArgumentCaptor.forClass(PostComment.class);
            verify(postCommentRepository).save(saved.capture());
            assertThat(saved.getValue().getParent()).isNull();
        }
    }

    // ─────────────────────────── deleteComment / editComment ───────────────────────────

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("comment not found → NotFoundException TM_221")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postCommentRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteComment("p", id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_221"));
        }

        @Test
        @DisplayName("deleting another user's comment → ForbiddenException TM_103")
        void notOwner() {
            PostComment c = comment(210L, owner, post(180L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.deleteComment("p", c.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(postCommentRepository, never()).save(any());
        }

        @Test
        @DisplayName("owner deletes their comment → soft-deleted and saved")
        void success() {
            PostComment c = comment(211L, currentUser, post(181L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));

            service.deleteComment("p", c.getUuid().toString(), currentUser);

            assertThat(c.isDeleted()).isTrue();
            verify(postCommentRepository).save(c);
        }
    }

    @Nested
    @DisplayName("editComment")
    class EditComment {

        private PostCommentRequest req(String content) {
            PostCommentRequest r = new PostCommentRequest();
            r.setContent(content);
            return r;
        }

        @Test
        @DisplayName("comment not found → NotFoundException TM_221")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postCommentRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.editComment("p", id.toString(), req("x"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_221"));
        }

        @Test
        @DisplayName("editing another user's comment → ForbiddenException TM_103")
        void notOwner() {
            PostComment c = comment(220L, owner, post(182L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.editComment("p", c.getUuid().toString(), req("y"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("owner edits → content updated and persisted")
        void success() {
            PostComment c = comment(221L, currentUser, post(183L, owner), "old", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));

            PostCommentResponse res = service.editComment("p", c.getUuid().toString(), req("new"), currentUser);

            assertThat(c.getContent()).isEqualTo("new");
            assertThat(res).isNotNull();
            verify(postCommentRepository).save(c);
        }
    }

    // ─────────────────────────── likeComment / unlikeComment ───────────────────────────

    @Nested
    @DisplayName("likeComment")
    class LikeComment {

        @Test
        @DisplayName("comment not found → NotFoundException TM_221")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postCommentRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.likeComment("p", id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_221"));
        }

        @Test
        @DisplayName("already liked → idempotent no-op")
        void alreadyLiked() {
            PostComment c = comment(230L, owner, post(190L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));
            when(postCommentLikeRepository.existsByCommentAndUser(c, currentUser)).thenReturn(true);

            service.likeComment("p", c.getUuid().toString(), currentUser);

            verify(postCommentLikeRepository, never()).save(any());
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("new like on another's comment → saved and comment author notified")
        void newLikeNotifiesAuthor() {
            Post p = post(191L, owner);
            PostComment c = comment(231L, owner, p, "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));
            when(postCommentLikeRepository.existsByCommentAndUser(c, currentUser)).thenReturn(false);

            service.likeComment("p", c.getUuid().toString(), currentUser);

            verify(postCommentLikeRepository).save(any(PostCommentLike.class));
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("LIKE"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("liking own comment → saved, no self-notification")
        void likeOwnCommentNoNotify() {
            Post p = post(192L, owner);
            PostComment c = comment(232L, currentUser, p, "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));
            when(postCommentLikeRepository.existsByCommentAndUser(c, currentUser)).thenReturn(false);

            service.likeComment("p", c.getUuid().toString(), currentUser);

            verify(postCommentLikeRepository).save(any(PostCommentLike.class));
            verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("unlikeComment")
    class UnlikeComment {

        @Test
        @DisplayName("comment not found → NotFoundException TM_221")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postCommentRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlikeComment("p", id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_221"));
        }

        @Test
        @DisplayName("existing comment like → deleted")
        void deletesExisting() {
            PostComment c = comment(240L, owner, post(193L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));
            PostCommentLike like = PostCommentLike.builder().comment(c).user(currentUser).build();
            when(postCommentLikeRepository.findByCommentAndUser(c, currentUser)).thenReturn(Optional.of(like));

            service.unlikeComment("p", c.getUuid().toString(), currentUser);

            verify(postCommentLikeRepository).delete(like);
        }

        @Test
        @DisplayName("no existing comment like → no delete")
        void noExisting() {
            PostComment c = comment(241L, owner, post(194L, owner), "x", null);
            when(postCommentRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));
            when(postCommentLikeRepository.findByCommentAndUser(c, currentUser)).thenReturn(Optional.empty());

            service.unlikeComment("p", c.getUuid().toString(), currentUser);

            verify(postCommentLikeRepository, never()).delete(any());
        }
    }

    // ─────────────────────────── bookmarkPost / unbookmarkPost ───────────────────────────

    @Nested
    @DisplayName("bookmarkPost")
    class BookmarkPost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.bookmarkPost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("already bookmarked → idempotent no-op")
        void alreadyBookmarked() {
            Post p = post(250L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postBookmarkRepository.existsByPostAndUser(p, currentUser)).thenReturn(true);

            service.bookmarkPost(p.getUuid().toString(), currentUser);

            verify(postBookmarkRepository, never()).save(any());
        }

        @Test
        @DisplayName("new bookmark → saved")
        void newBookmark() {
            Post p = post(251L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postBookmarkRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.bookmarkPost(p.getUuid().toString(), currentUser);

            verify(postBookmarkRepository).save(any(PostBookmark.class));
        }
    }

    @Nested
    @DisplayName("unbookmarkPost")
    class UnbookmarkPost {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unbookmarkPost(id.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("existing bookmark → deleted")
        void deletesExisting() {
            Post p = post(260L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostBookmark bm = PostBookmark.builder().post(p).user(currentUser).build();
            when(postBookmarkRepository.findByPostAndUser(p, currentUser)).thenReturn(Optional.of(bm));

            service.unbookmarkPost(p.getUuid().toString(), currentUser);

            verify(postBookmarkRepository).delete(bm);
        }

        @Test
        @DisplayName("no existing bookmark → no delete")
        void noExisting() {
            Post p = post(261L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postBookmarkRepository.findByPostAndUser(p, currentUser)).thenReturn(Optional.empty());

            service.unbookmarkPost(p.getUuid().toString(), currentUser);

            verify(postBookmarkRepository, never()).delete(any());
        }
    }

    // ─────────────────────────── getComments / getReplies ───────────────────────────

    @Nested
    @DisplayName("getComments")
    class GetComments {

        @Test
        @DisplayName("post not found → NotFoundException TM_211")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getComments(id.toString(), pageable, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("top-level comments mapped to responses")
        void mapsComments() {
            Post p = post(270L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment c = comment(280L, owner, p, "hi", null);
            when(postCommentRepository.findByPostAndParentIsNull(p, pageable))
                    .thenReturn(new PageImpl<>(List.of(c)));

            Page<PostCommentResponse> page = service.getComments(p.getUuid().toString(), pageable, currentUser);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getContent()).isEqualTo("hi");
        }
    }

    @Nested
    @DisplayName("getReplies")
    class GetReplies {

        @Test
        @DisplayName("parent comment not found → NotFoundException TM_221")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(postCommentRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReplies("p", id.toString(), pageable, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_221"));
        }

        @Test
        @DisplayName("replies to a parent comment mapped to responses")
        void mapsReplies() {
            Post p = post(271L, owner);
            PostComment parent = comment(281L, owner, p, "parent", null);
            when(postCommentRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));
            PostComment reply = comment(282L, currentUser, p, "reply", parent);
            when(postCommentRepository.findReplies(parent, pageable))
                    .thenReturn(new PageImpl<>(List.of(reply)));

            Page<PostCommentResponse> page = service.getReplies("p", parent.getUuid().toString(),
                    pageable, currentUser);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getParentId()).isEqualTo(parent.getUuid().toString());
        }
    }

    // ═══════════════════ branch-coverage backfill (negative / edge paths) ═══════════════════

    /**
     * Build a PostMedia and attach it to a post (for firstThumb / mapToPostResponse paths).
     */
    private PostMedia media(Post post, String mediaUrl, String mediaType, String coverImageUrl) {
        PostMedia m = PostMedia.builder()
                .post(post).mediaUrl(mediaUrl).mediaType(mediaType).coverImageUrl(coverImageUrl)
                .build();
        m.setUuid(UUID.randomUUID());
        post.getMedia().add(m);
        return m;
    }

    @Nested
    @DisplayName("createPost — content/media/audience/TTL guard edges")
    class CreatePostGuardEdges {

        @Test
        @DisplayName("content present but blank → hasContent false (saved via media instead)")
        void contentBlankNotCounted() {
            PostRequest r = new PostRequest();
            r.setContent("   ");
            r.setMedia(List.of(new PostMediaRequest("http://img/1.jpg", "IMAGE",
                    null, null, null, null, null)));

            service.createPost(r, currentUser);

            verify(postMediaRepository).save(any(PostMedia.class));
        }

        @Test
        @DisplayName("media non-null but empty list → hasMedia false (text carries the post)")
        void mediaEmptyListNotCounted() {
            PostRequest r = textRequest("just text");
            r.setMedia(new ArrayList<>());

            service.createPost(r, currentUser);

            verify(postMediaRepository, never()).save(any());
            verify(postRepository).save(any(Post.class));
        }

        @Test
        @DisplayName("audience non-null but not 'FRIENDS' → stays EVERYONE")
        void audienceNonFriendsValueStaysEveryone() {
            PostRequest r = textRequest("open");
            r.setAudience("PUBLIC");

            service.createPost(r, currentUser);

            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            assertThat(saved.getValue().getAudience()).isEqualTo(PostAudience.EVERYONE);
        }

        @Test
        @DisplayName("expiresInSeconds present but <= 0 → no TTL, feature access not consulted")
        void expiresNonPositiveNoTtl() {
            PostRequest r = textRequest("no ttl");
            r.setExpiresInSeconds(0);

            service.createPost(r, currentUser);

            ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
            verify(postRepository).save(saved.capture());
            assertThat(saved.getValue().getExpiresAt()).isNull();
            verify(featureAccessService, never()).hasAccess(any(), any());
        }

        @Test
        @DisplayName("clean non-blank separate caption → passes moderation, post saved")
        void cleanCaptionPasses() {
            PostRequest r = textRequest("body");
            r.setCaption("nice caption");

            PostResponse res = service.createPost(r, currentUser);

            assertThat(res).isNotNull();
            verify(postRepository).save(any(Post.class));
        }

        @Test
        @DisplayName("blank separate caption → moderation skipped for it (only content moderated)")
        void blankCaptionSkipsCaptionModeration() {
            PostRequest r = textRequest("body");
            r.setCaption("   ");

            service.createPost(r, currentUser);

            // content moderated once; caption (blank) never handed to moderation.
            verify(moderationService).moderateText("body");
            verify(moderationService, never()).moderateText("   ");
        }

        /**
         * The share short-code generator probes for uniqueness; a first collision forces at least
         * a second existsByShortCode round before a free code is accepted.
         */
        @Test
        @DisplayName("short-code collision → ShortCodes.unique retries until a free code")
        void shortCodeCollisionRetries() {
            when(postRepository.existsByShortCode(any())).thenReturn(true, false);

            service.createPost(textRequest("retry"), currentUser);

            verify(postRepository, Mockito.atLeast(2)).existsByShortCode(any());
        }
    }

    @Nested
    @DisplayName("createPost — photo+music mux edges")
    class CreatePostMuxEdges {

        private PostRequest imageWithAudio(String imageUrl, String audioUrl,
                                           Integer startSec, Integer clipSec) {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest(imageUrl, "IMAGE", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().audioUrl(audioUrl)
                    .audioStartSec(startSec).audioClipSeconds(clipSec).build());
            return r;
        }

        @Test
        @DisplayName("audio present but audioUrl null → mux skipped and no AudioTrack stored")
        void audioWithoutUrlSkipsMuxAndTrack() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://img/1.jpg", "IMAGE", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().build()); // audioUrl == null

            service.createPost(r, currentUser);

            verify(photoMusicMuxer, never()).muxPhotoWithMusic(any(), any(),
                    ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
            // toEntity() returns null when audioUrl is blank → only the initial save happens.
            verify(postRepository, times(1)).save(any(Post.class));
        }

        @Test
        @DisplayName("audio + more than one media item → single-image mux path skipped")
        void audioWithTwoMediaSkipsMux() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://img/1.jpg", "IMAGE", null, null, null, null, null),
                    new PostMediaRequest("http://img/2.jpg", "IMAGE", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().audioUrl("http://audio/song.mp3").build());

            service.createPost(r, currentUser);

            verify(photoMusicMuxer, never()).muxPhotoWithMusic(any(), any(),
                    ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
            verify(postMediaRepository, times(2)).save(any(PostMedia.class));
        }

        @Test
        @DisplayName("single VIDEO + audio → not an image, mux path skipped")
        void singleVideoWithAudioSkipsMux() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://vid/clip.mp4", "VIDEO", null, null, null, null, null))));
            r.setAudio(AudioTrackDto.builder().audioUrl("http://audio/song.mp3").build());

            service.createPost(r, currentUser);

            verify(photoMusicMuxer, never()).muxPhotoWithMusic(any(), any(),
                    ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("resolvable clean source image + null start/clip → muxed with defaults 0/15")
        void cleanImageMuxesWithDefaultStartClip() {
            PostRequest r = imageWithAudio("http://img/still.jpg", "http://audio/song.mp3", null, null);
            MediaStorage.LocalFile local = mock(MediaStorage.LocalFile.class);
            lenient().when(local.path()).thenReturn(Path.of("/tmp/still.jpg"));
            when(mediaStorage.localCopy("http://img/still.jpg")).thenReturn(Optional.of(local));
            when(moderationService.moderateMedia(Path.of("/tmp/still.jpg"), MessageType.IMAGE))
                    .thenReturn(ModerationResult.clean());
            when(photoMusicMuxer.muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 0, 15))
                    .thenReturn("http://media/muxed.mp4");

            service.createPost(r, currentUser);

            verify(photoMusicMuxer).muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 0, 15);
            ArgumentCaptor<PostMedia> saved = ArgumentCaptor.forClass(PostMedia.class);
            verify(postMediaRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaType()).isEqualTo("VIDEO");
        }

        /**
         * When the muxer fails and returns null, the post degrades gracefully to the original
         * still image rather than dropping the media — type stays IMAGE.
         */
        @Test
        @DisplayName("mux returns null → falls back to the plain image (stays IMAGE)")
        void muxReturnsNullFallsBackToImage() {
            PostRequest r = imageWithAudio("http://img/still.jpg", "http://audio/song.mp3", 2, 10);
            // localCopy unresolved → moderation skipped; mux yields null → fallback.
            when(photoMusicMuxer.muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 2, 10))
                    .thenReturn(null);

            service.createPost(r, currentUser);

            verify(photoMusicMuxer).muxPhotoWithMusic("http://img/still.jpg", "http://audio/song.mp3", 2, 10);
            ArgumentCaptor<PostMedia> saved = ArgumentCaptor.forClass(PostMedia.class);
            verify(postMediaRepository).save(saved.capture());
            assertThat(saved.getValue().getMediaType()).isEqualTo("IMAGE");
        }
    }

    @Nested
    @DisplayName("createPost — media loop + poll edges")
    class CreatePostMediaAndPollEdges {

        @Test
        @DisplayName("media item with null url → not treated as muxed output, saved without moderation")
        void mediaWithNullUrlSaved() {
            PostRequest r = new PostRequest();
            r.setContent("has media");
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest(null, "IMAGE", null, null, null, null, null))));

            service.createPost(r, currentUser);

            verify(postMediaRepository).save(any(PostMedia.class));
            verify(moderationService, never()).moderateMedia(any(), any());
        }

        @Test
        @DisplayName("resolvable VIDEO media that is clean → moderated as VIDEO and saved")
        void videoMediaModeratedCleanSaved() {
            PostRequest r = new PostRequest();
            r.setMedia(new ArrayList<>(List.of(
                    new PostMediaRequest("http://vid/clip.mp4", "VIDEO", null, null, null, null, null))));
            MediaStorage.LocalFile local = mock(MediaStorage.LocalFile.class);
            when(local.path()).thenReturn(Path.of("/tmp/clip.mp4"));
            when(mediaStorage.localCopy("http://vid/clip.mp4")).thenReturn(Optional.of(local));
            when(moderationService.moderateMedia(Path.of("/tmp/clip.mp4"), MessageType.VIDEO))
                    .thenReturn(ModerationResult.clean());

            service.createPost(r, currentUser);

            verify(moderationService).moderateMedia(Path.of("/tmp/clip.mp4"), MessageType.VIDEO);
            verify(postMediaRepository).save(any(PostMedia.class));
        }

        @Test
        @DisplayName("poll with null options list → fewer than 2 → BadRequestException TM_225")
        void pollNullOptions() {
            PostRequest r = new PostRequest();
            r.setPoll(new PollRequest("Q?", null));

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_225"));
            verify(pollRepository, never()).save(any());
        }

        @Test
        @DisplayName("poll question clean but an option explicit → ContentModerationException TM_490")
        void pollExplicitOption() {
            when(moderationService.moderateText("badopt")).thenReturn(explicit());
            PostRequest r = new PostRequest();
            r.setPoll(new PollRequest("Q?", List.of("ok", "badopt")));

            assertThatThrownBy(() -> service.createPost(r, currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(pollRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("firstThumb (via like-notification thumbnail)")
    class FirstThumbBackfill {

        @Test
        @DisplayName("media with a non-blank cover image → cover used as the thumbnail")
        void coverImageUsedAsThumb() {
            Post p = post(300L, owner);
            media(p, "http://m/pic.jpg", "IMAGE", "http://m/cover.jpg");
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("LIKE"), any(), eq(currentUser), eq("http://m/cover.jpg"));
        }

        @Test
        @DisplayName("media with null cover → falls back to the media url as the thumbnail")
        void nullCoverFallsBackToMediaUrl() {
            Post p = post(301L, owner);
            media(p, "http://m/pic.jpg", "IMAGE", null);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("LIKE"), any(), eq(currentUser), eq("http://m/pic.jpg"));
        }

        @Test
        @DisplayName("media with blank cover → falls back to the media url as the thumbnail")
        void blankCoverFallsBackToMediaUrl() {
            Post p = post(302L, owner);
            media(p, "http://m/pic2.jpg", "IMAGE", "   ");
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.existsByPostAndUser(p, currentUser)).thenReturn(false);

            service.likePost(p.getUuid().toString(), currentUser);

            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("LIKE"), any(), eq(currentUser), eq("http://m/pic2.jpg"));
        }
    }

    @Nested
    @DisplayName("canViewPost / getPostByShortCode — remaining branches")
    class ViewAndShortCodeBackfill {

        @Test
        @DisplayName("expired post by short code → NotFoundException TM_211")
        void expiredByShortCode() {
            Post p = post(310L, owner);
            p.setExpiresAt(Instant.now().minusSeconds(30));
            when(postRepository.findByShortCode("exp")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPostByShortCode("exp", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("FRIENDS post by short code viewed by a stranger → NotFoundException TM_211")
        void friendsHiddenByShortCode() {
            Post p = post(311L, owner);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByShortCode("fr")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPostByShortCode("fr", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("FRIENDS post with a null viewer → hidden (NotFoundException TM_211)")
        void friendsNullViewerHidden() {
            Post p = post(312L, owner);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByShortCode("fr2")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPostByShortCode("fr2", null))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_211"));
        }

        @Test
        @DisplayName("FRIENDS post visible via a reverse-direction accepted follow (owner→viewer)")
        void friendsVisibleViaReverseFollow() {
            Post p = post(313L, owner);
            p.setAudience(PostAudience.FRIENDS);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            // forward (viewer→owner) absent; reverse (owner→viewer) accepted.
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(
                    currentUser, owner, "ACCEPTED")).thenReturn(false);
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(
                    owner, currentUser, "ACCEPTED")).thenReturn(true);

            assertThat(service.getPost(p.getUuid().toString(), currentUser)).isNotNull();
        }
    }

    @Nested
    @DisplayName("updatePost — caption-absent branch")
    class UpdatePostBackfill {

        @Test
        @DisplayName("owner updates content only (no caption field) → caption left untouched")
        void contentOnlyNoCaption() {
            Post p = post(320L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            service.updatePost(p.getUuid().toString(), textRequest("just body"), currentUser);

            assertThat(p.getContent()).isEqualTo("just body");
            assertThat(p.getCaption()).isNull();
            verify(postRepository).save(p);
        }
    }

    @Nested
    @DisplayName("mapToPostResponse / isFriendsOnly — remaining branches")
    class MapToPostResponseBackfill {

        @Test
        @DisplayName("post with a null audience → response reports EVERYONE")
        void nullAudienceMapsToEveryone() {
            Post p = post(330L, owner);
            p.setAudience(null);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            PostResponse res = service.getPost(p.getUuid().toString(), currentUser);

            assertThat(res.getAudience()).isEqualTo("EVERYONE");
        }

        @Test
        @DisplayName("post with a top-level comment → comment mapped into the response")
        void topLevelCommentMapped() {
            Post p = post(331L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment c = comment(400L, owner, p, "first!", null);
            when(postCommentRepository.findByPostAndParentNullOrderByCreatedAtAsc(p))
                    .thenReturn(List.of(c));

            PostResponse res = service.getPost(p.getUuid().toString(), currentUser);

            assertThat(res.getComments()).hasSize(1);
            assertThat(res.getComments().get(0).getContent()).isEqualTo("first!");
        }

        @Test
        @DisplayName("liker whose privacy is EVERYONE → friends-only flag is false")
        void likerNotFriendsOnlyFlagFalse() {
            Post p = post(332L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            User liker = user(9L, "dave");
            PostLike like = PostLike.builder().post(p).user(liker).build();
            when(postLikeRepository.findByPost(p, pageable)).thenReturn(new PageImpl<>(List.of(like)));
            UserSetting setting = new UserSetting();
            setting.setMessagingPrivacy(MessagingPrivacy.EVERYONE);
            when(userSettingRepository.findByUser(liker)).thenReturn(Optional.of(setting));

            Page<AuthUserResponse> page = service.getPostLikes(p.getUuid().toString(), pageable, currentUser);

            assertThat(page.getContent().get(0).getMessagingFriendsOnly()).isFalse();
        }
    }

    @Nested
    @DisplayName("addComment — snippet + reply-notify edges")
    class AddCommentBackfill {

        private PostCommentRequest req(String content, String parentId) {
            PostCommentRequest r = new PostCommentRequest();
            r.setContent(content);
            r.setParentId(parentId);
            return r;
        }

        @Test
        @DisplayName("blank parentId → treated as a top-level comment")
        void blankParentIdTreatedTopLevel() {
            Post p = post(340L, currentUser);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            service.addComment(p.getUuid().toString(), req("hey", "   "), currentUser);

            ArgumentCaptor<PostComment> saved = ArgumentCaptor.forClass(PostComment.class);
            verify(postCommentRepository).save(saved.capture());
            assertThat(saved.getValue().getParent()).isNull();
            verify(postCommentRepository, never()).findByUuid(any());
        }

        @Test
        @DisplayName("null content → empty snippet path, still saved + owner notified")
        void nullContentSnippet() {
            Post p = post(341L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));

            service.addComment(p.getUuid().toString(), req(null, null), currentUser);

            verify(postCommentRepository).save(any(PostComment.class));
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("content longer than 80 chars → snippet truncated, still saved + notified")
        void longContentTruncatedSnippet() {
            Post p = post(342L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            String longText = "x".repeat(120);

            service.addComment(p.getUuid().toString(), req(longText, null), currentUser);

            verify(postCommentRepository).save(any(PostComment.class));
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("reply to own comment → parent author (self) not notified")
        void replyToOwnCommentSkipsParentNotify() {
            Post p = post(343L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment parent = comment(410L, currentUser, p, "mine", null);
            when(postCommentRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));

            service.addComment(p.getUuid().toString(), req("self reply", parent.getUuid().toString()), currentUser);

            // only the post owner is notified; the parent author is the commenter → skipped.
            verify(notificationService, times(1)).createNotification(any(), any(), any(),
                    any(), any(), any(), any());
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        /**
         * When the parent comment's author and the post owner are the same person, the two
         * notification targets collapse — that recipient is notified exactly once, not twice.
         */
        @Test
        @DisplayName("reply where the parent author is the post owner → only notified once")
        void replyWhereParentAuthorIsPostOwner() {
            Post p = post(344L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment parent = comment(411L, owner, p, "owner comment", null);
            when(postCommentRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));

            service.addComment(p.getUuid().toString(), req("reply", parent.getUuid().toString()), currentUser);

            verify(notificationService, times(1)).createNotification(any(), any(), any(),
                    any(), any(), any(), any());
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }

        @Test
        @DisplayName("reply where the parent comment has a null author → parent notify skipped")
        void replyWhereParentUserNull() {
            Post p = post(345L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment parent = PostComment.builder().post(p).user(null).content("orphan").build();
            parent.setUuid(UUID.randomUUID());
            parent.setCreatedAt(Instant.now());
            when(postCommentRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));

            service.addComment(p.getUuid().toString(), req("reply", parent.getUuid().toString()), currentUser);

            // post owner notified; parent-author branch short-circuits on the null user.
            verify(notificationService, times(1)).createNotification(any(), any(), any(),
                    any(), any(), any(), any());
            verify(notificationService).createNotification(eq(owner), any(), any(),
                    eq("COMMENT"), any(), eq(currentUser), any());
        }
    }

    @Nested
    @DisplayName("mapToCommentResponse — likedByMe branches (via getComments)")
    class MapToCommentResponseBackfill {

        @Test
        @DisplayName("null current user → likedByMe false without touching the like repo")
        void nullCurrentUserLikedByMeFalse() {
            Post p = post(350L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment c = comment(420L, owner, p, "hi", null);
            when(postCommentRepository.findByPostAndParentIsNull(p, pageable))
                    .thenReturn(new PageImpl<>(List.of(c)));

            Page<PostCommentResponse> page = service.getComments(p.getUuid().toString(), pageable, null);

            assertThat(page.getContent().get(0).isLikedByMe()).isFalse();
            verify(postCommentLikeRepository, never()).existsByCommentAndUser(any(), any());
        }

        @Test
        @DisplayName("comment liked by the current user → likedByMe true")
        void likedByCurrentUserTrue() {
            Post p = post(351L, owner);
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            PostComment c = comment(421L, owner, p, "hi", null);
            when(postCommentRepository.findByPostAndParentIsNull(p, pageable))
                    .thenReturn(new PageImpl<>(List.of(c)));
            when(postCommentLikeRepository.existsByCommentAndUser(c, currentUser)).thenReturn(true);

            Page<PostCommentResponse> page = service.getComments(p.getUuid().toString(), pageable, currentUser);

            assertThat(page.getContent().get(0).isLikedByMe()).isTrue();
        }
    }
}
