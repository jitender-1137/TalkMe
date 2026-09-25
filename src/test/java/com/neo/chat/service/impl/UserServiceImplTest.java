package com.neo.chat.service.impl;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.FriendRequest;
import com.neo.chat.enums.FriendRequestStatus;
import com.neo.chat.domain.MatchReport;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.request.UpdateProfileRequest;
import com.neo.chat.dto.response.BlockedUserResponse;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.MutualFriendsResponse;
import com.neo.chat.dto.response.PaginatedResponse;
import com.neo.chat.dto.response.PublicProfileResponse;
import com.neo.chat.dto.response.ReputationResponse;
import com.neo.chat.dto.response.SmartProfileCardResponse;
import com.neo.chat.dto.response.StreakResponse;
import com.neo.chat.dto.response.UserResponse;
import com.neo.chat.enums.ConversationEnergy;
import com.neo.chat.enums.Interest;
import com.neo.chat.enums.Language;
import com.neo.chat.enums.LookingForTag;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.Mood;
import com.neo.chat.enums.PersonalityTrait;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.FriendRequestRepository;
import com.neo.chat.repository.MatchReportRepository;
import com.neo.chat.repository.PostRepository;
import com.neo.chat.repository.UserFollowRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.NotificationService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.ReputationRecorder;
import com.neo.chat.service.ReputationService;
import com.neo.chat.service.StorageService;
import com.neo.chat.service.StreakService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link UserServiceImpl} — profile read/update, avatar upload/removal,
 * user lookup (self / uuid / public / smart-card), search, blocked list, reporting, mutual friends
 * and lobby listing, plus the shared presence/block-status and follower/post count enrichment.
 *
 * <p>Shared enrichment collaborators (mapper, presence, settings, block, follow/post counts,
 * friend lookup) are stubbed leniently with benign defaults in {@link #setUp()} because almost
 * every method funnels responses through {@code populatePresenceAndBlockStatus} /
 * {@code populateUserCounts}; individual tests override only what they assert on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl (unit)")
class UserServiceImplTest {

    private static final UUID TARGET_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-07-30T10:15:30Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private FriendRequestRepository friendRequestRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private MatchReportRepository matchReportRepository;
    @Mock
    private PresenceService presenceService;
    @Mock
    private StorageService storageService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ReputationRecorder reputationRecorder;
    @Mock
    private ReputationService reputationService;
    @Mock
    private CompatibilityService compatibilityService;
    @Mock
    private StreakService streakService;
    @Mock
    private SetOperations<String, String> setOps;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(
                userRepository, friendRepository, friendRequestRepository, userSettingRepository,
                blockUserRepository, matchReportRepository, presenceService, storageService, userMapper,
                redisTemplate, userFollowRepository, postRepository, moderationService, notificationService,
                reputationRecorder, reputationService, compatibilityService, streakService);

        // ── Shared enrichment defaults (populate* helpers run on almost every path) ──
        lenient().when(userMapper.toUserResponse(any(User.class)))
                .thenAnswer(inv -> UserResponse.builder().build());
        lenient().when(presenceService.getStatus(any(User.class))).thenReturn(PresenceStatus.ONLINE);
        lenient().when(presenceService.getLastSeen(any(User.class))).thenReturn(NOW);
        lenient().when(presenceService.getApparentLastSeen(any(User.class))).thenReturn(NOW);
        lenient().when(userSettingRepository.findByUser(any(User.class))).thenReturn(Optional.empty());
        lenient().when(blockUserRepository.existsByUserAndBlocked(any(User.class), any(User.class)))
                .thenReturn(false);
        lenient().when(friendRepository.findByUserAndFriend(any(User.class), any(User.class)))
                .thenReturn(Optional.empty());
        lenient().when(friendRepository.findFriendsByUser(any(User.class))).thenReturn(List.of());
        lenient().when(userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(any(User.class), eq("ACCEPTED")))
                .thenReturn(0L);
        lenient().when(userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(any(User.class), eq("ACCEPTED")))
                .thenReturn(0L);
        lenient().when(postRepository.countVisibleByUser(any(User.class))).thenReturn(0L);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private User user(long id, UUID uuid, String name) {
        User u = User.builder()
                .username("user" + id)
                .email("user" + id + "@talk.me")
                .name(name)
                .build();
        u.setId(id);
        u.setUuid(uuid);
        return u;
    }

    private User viewer() {
        return user(1L, VIEWER_UUID, "Viewer");
    }

    private User target() {
        return user(2L, TARGET_UUID, "Target");
    }

    /**
     * A fully-populated user whose {@link com.neo.chat.util.ProfileCompletion} score is 100.
     */
    private User fullyCompletedUser(long id, UUID uuid) {
        User u = user(id, uuid, "Complete");
        u.setProfileImage("https://cdn/a.png");
        u.setBio("hello world");
        u.getInterests().addAll(Set.of(Interest.SPORTS, Interest.MUSIC, Interest.MOVIES));
        u.setMood(Mood.FLIRT);
        u.setConversationEnergy(ConversationEnergy.CHILL);
        u.getLanguages().add(Language.EN);
        u.getLookingFor().add(LookingForTag.FRIENDS);
        u.setVoiceIntroUrl("https://cdn/v.mp3");
        return u;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("maps the reloaded user, forces presence=online and populates counts")
        void returnsCurrentUser() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(me, "ACCEPTED")).thenReturn(7L);
            when(userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(me, "ACCEPTED")).thenReturn(3L);
            when(postRepository.countVisibleByUser(me)).thenReturn(5L);

            UserResponse res = service.getCurrentUser(me);

            assertThat(res.getPresence()).isEqualTo("online");
            assertThat(res.getLastSeen()).isNotNull();
            assertThat(res.getFollowersCount()).isEqualTo(7L);
            assertThat(res.getFollowingCount()).isEqualTo(3L);
            assertThat(res.getPostsCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("user no longer exists → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCurrentUser(me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("applies all provided scalar fields and saves")
        void updatesScalars() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateText("Neo")).thenReturn(ModerationResult.clean());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            UpdateProfileRequest req = UpdateProfileRequest.builder()
                    .name("Neo").profileImage("img.png").city("Delhi")
                    .mobileNumber("111").age(28).gender("male").bio("bio")
                    .occupation("dev").education("uni")
                    .voiceIntroUrl("v.mp3").voiceIntroDurationMs(2000)
                    .build();

            UserResponse res = service.updateProfile(req, me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            User u = saved.getValue();
            assertThat(u.getName()).isEqualTo("Neo");
            assertThat(u.getProfileImage()).isEqualTo("img.png");
            assertThat(u.getCity()).isEqualTo("Delhi");
            assertThat(u.getMobileNumber()).isEqualTo("111");
            assertThat(u.getAge()).isEqualTo(28);
            assertThat(u.getGender()).isEqualTo("male");
            assertThat(u.getBio()).isEqualTo("bio");
            assertThat(u.getOccupation()).isEqualTo("dev");
            assertThat(u.getEducation()).isEqualTo("uni");
            assertThat(u.getVoiceIntroUrl()).isEqualTo("v.mp3");
            assertThat(u.getVoiceIntroDurationMs()).isEqualTo(2000);
            assertThat(res.getPresence()).isEqualTo("online");
        }

        @Test
        @DisplayName("phone falls through to mobileNumber")
        void phoneMapsToMobile() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateProfile(UpdateProfileRequest.builder().phone("999").build(), me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getMobileNumber()).isEqualTo("999");
        }

        @Test
        @DisplayName("collections + mood + energy replace and personality is clamped 0..100")
        void updatesCollectionsAndClampsPersonality() {
            User me = viewer();
            me.getInterests().add(Interest.FOOD); // pre-existing, must be cleared
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            UpdateProfileRequest req = UpdateProfileRequest.builder()
                    .interests(Set.of(Interest.MUSIC, Interest.GAMING))
                    .languages(Set.of(Language.EN))
                    .lookingFor(Set.of(LookingForTag.DATING))
                    .mood(Mood.DEEP)
                    .conversationEnergy(ConversationEnergy.DEEP)
                    .personality(Map.of(PersonalityTrait.OPENNESS, 250, PersonalityTrait.EXTRAVERSION, -30))
                    .build();

            service.updateProfile(req, me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            User u = saved.getValue();
            assertThat(u.getInterests()).containsExactlyInAnyOrder(Interest.MUSIC, Interest.GAMING);
            assertThat(u.getLanguages()).containsExactly(Language.EN);
            assertThat(u.getLookingFor()).containsExactly(LookingForTag.DATING);
            assertThat(u.getMood()).isEqualTo(Mood.DEEP);
            assertThat(u.getMoodUpdatedAt()).isNotNull();
            assertThat(u.getConversationEnergy()).isEqualTo(ConversationEnergy.DEEP);
            assertThat(u.getPersonality().get(PersonalityTrait.OPENNESS)).isEqualTo(100);
            assertThat(u.getPersonality().get(PersonalityTrait.EXTRAVERSION)).isEqualTo(0);
        }

        @Test
        @DisplayName("reaching 100% completion records a PROFILE_COMPLETED reputation event")
        void recordsReputationWhenComplete() {
            User me = fullyCompletedUser(1L, VIEWER_UUID);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateProfile(UpdateProfileRequest.builder().build(), me);

            verify(reputationRecorder).record(1L, ReputationEventType.PROFILE_COMPLETED, "1");
        }

        @Test
        @DisplayName("an incomplete profile does NOT record a reputation event")
        void noReputationWhenIncomplete() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateProfile(UpdateProfileRequest.builder().build(), me);

            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("explicit display name → ContentModerationException TM_490, nothing saved")
        void rejectsExplicitName() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateText("badword"))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.PROFANITY, 0.99, List.of()));

            assertThatThrownBy(() -> service.updateProfile(
                    UpdateProfileRequest.builder().name("badword").build(), me))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("changing country → BadRequestException TM_099")
        void rejectsCountryChange() {
            User me = viewer();
            me.setCountry("US");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.updateProfile(
                    UpdateProfileRequest.builder().country("UK").build(), me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_099"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("same country value is a no-op (not rejected)")
        void sameCountryAllowed() {
            User me = viewer();
            me.setCountry("US");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateProfile(UpdateProfileRequest.builder().country("US").build(), me);

            verify(userRepository).save(any());
        }

        @Test
        @DisplayName("blank gender is ignored (kept)")
        void blankGenderIgnored() {
            User me = viewer();
            me.setGender("female");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateProfile(UpdateProfileRequest.builder().gender("   ").build(), me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getGender()).isEqualTo("female");
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateProfile(UpdateProfileRequest.builder().build(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateMood")
    class UpdateMood {

        @Test
        @DisplayName("valid mood (case-insensitive, trimmed) is set and saved")
        void setsMood() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateMood("  flirt ", me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getMood()).isEqualTo(Mood.FLIRT);
            assertThat(saved.getValue().getMoodUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("unknown mood value → BadRequestException TM_002 (before lookup)")
        void invalidMood() {
            User me = viewer();

            assertThatThrownBy(() -> service.updateMood("bogus", me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_002"));
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("null mood value → BadRequestException TM_002")
        void nullMood() {
            User me = viewer();

            assertThatThrownBy(() -> service.updateMood(null, me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_002"));
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateMood("FLIRT", me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("uploadAvatar")
    class UploadAvatar {

        @Mock
        private MultipartFile file;

        private static final byte[] PNG = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        @org.junit.jupiter.api.BeforeEach
        void stubImageBytes() throws java.io.IOException {
            // uploadAvatar now magic-byte-validates the file; feed it real PNG bytes.
            org.mockito.Mockito.lenient().when(file.getInputStream())
                    .thenAnswer(inv -> new java.io.ByteArrayInputStream(PNG));
        }

        @Test
        @DisplayName("clean new photo → stores, saves, notifies friends and returns url")
        void storesAndNotifies() {
            User me = viewer();
            me.setProfileImage("old.png");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateUpload(file)).thenReturn(ModerationResult.clean());
            when(storageService.storeFile(file, "avatar", "profiles/" + VIEWER_UUID)).thenReturn("new.png");

            Map<String, String> res = service.uploadAvatar(file, me);

            assertThat(res).containsEntry("avatarUrl", "new.png");
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getProfileImage()).isEqualTo("new.png");
            verify(notificationService).notifyFriends(eq(me), eq("New profile photo"), anyString(),
                    eq("PROFILE_PHOTO"), eq(VIEWER_UUID.toString()), eq("new.png"));
        }

        @Test
        @DisplayName("re-uploading the SAME url is not a new photo → no notification")
        void samePhotoNoNotify() {
            User me = viewer();
            me.setProfileImage("same.png");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateUpload(file)).thenReturn(ModerationResult.clean());
            when(storageService.storeFile(file, "avatar", "profiles/" + VIEWER_UUID)).thenReturn("same.png");

            service.uploadAvatar(file, me);

            verify(notificationService, never()).notifyFriends(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("guest never notifies friends even on a new photo")
        void guestNoNotify() {
            User me = viewer();
            me.setGuest(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateUpload(file)).thenReturn(ModerationResult.clean());
            when(storageService.storeFile(file, "avatar", "profiles/" + VIEWER_UUID)).thenReturn("new.png");

            service.uploadAvatar(file, me);

            verify(notificationService, never()).notifyFriends(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("notification failure is swallowed — upload still returns the url")
        void notifyFailureSwallowed() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateUpload(file)).thenReturn(ModerationResult.clean());
            when(storageService.storeFile(file, "avatar", "profiles/" + VIEWER_UUID)).thenReturn("new.png");
            Mockito.doThrow(new RuntimeException("push down"))
                    .when(notificationService).notifyFriends(any(), any(), any(), any(), any(), any());

            Map<String, String> res = service.uploadAvatar(file, me);

            assertThat(res).containsEntry("avatarUrl", "new.png");
        }

        @Test
        @DisplayName("NSFW upload → ContentModerationException TM_490, nothing stored")
        void rejectsNsfw() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(moderationService.moderateUpload(file))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.NSFW_IMAGE, 0.9, List.of()));

            assertThatThrownBy(() -> service.uploadAvatar(file, me))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(storageService, never()).storeFile(any(), any(), any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.uploadAvatar(file, me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("removeAvatar")
    class RemoveAvatar {

        @Test
        @DisplayName("nulls the profile image and saves, without notifying")
        void removes() {
            User me = viewer();
            me.setProfileImage("x.png");
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));

            service.removeAvatar(me);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getProfileImage()).isNull();
            verify(notificationService, never()).notifyFriends(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeAvatar(me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserById")
    class GetUserById {

        @Test
        @DisplayName("\"me\" resolves to the current user (no repo lookup)")
        void meBranch() {
            User me = viewer();

            UserResponse res = service.getUserById("me", me);

            assertThat(res).isNotNull();
            verify(userRepository, never()).findByUuid(any());
            // Owner viewing self → not a friend, own last-seen used.
            assertThat(res.isFriend()).isFalse();
        }

        @Test
        @DisplayName("peer response redacts phone + roles; self keeps them")
        void peerPiiRedacted() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            // Mapper yields a response carrying PII (as the real mapper does from mobileNumber/roles).
            when(userMapper.toUserResponse(t)).thenReturn(
                    UserResponse.builder().phone("+15551234567").roles(java.util.List.of("ROLE_USER")).build());

            UserResponse peer = service.getUserById(TARGET_UUID.toString(), me);
            assertThat(peer.getPhone()).isNull();
            assertThat(peer.getRoles()).isNull();

            // Self path keeps phone/roles.
            when(userMapper.toUserResponse(me)).thenReturn(
                    UserResponse.builder().phone("+15559999999").roles(java.util.List.of("ROLE_USER")).build());
            UserResponse self = service.getUserById("me", me);
            assertThat(self.getPhone()).isEqualTo("+15559999999");
            assertThat(self.getRoles()).containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("uuid lookup: friend relationship sets isFriend=true")
        void uuidFriend() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(friendRepository.findByUserAndFriend(me, t)).thenReturn(Optional.of(new Friend()));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isFriend()).isTrue();
        }

        @Test
        @DisplayName("uuid lookup: no friendship → isFriend=false")
        void uuidNotFriend() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isFriend()).isFalse();
        }

        @Test
        @DisplayName("viewer follows target → isFollowing=true")
        void isFollowingTrue() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(me, t, "ACCEPTED"))
                    .thenReturn(true);

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isFollowing()).isTrue();
        }

        @Test
        @DisplayName("not following → isFollowing=false")
        void isFollowingFalse() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isFollowing()).isFalse();
        }

        @Test
        @DisplayName("pending request I sent → friendRequestOutgoingId set, incoming null")
        void outgoingPending() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            FriendRequest out = FriendRequest.builder().sender(me).receiver(t)
                    .status(FriendRequestStatus.PENDING).build();
            UUID outId = UUID.randomUUID();
            out.setUuid(outId);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, t))
                    .thenReturn(Optional.of(out));
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(t, me))
                    .thenReturn(Optional.empty());

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getFriendRequestOutgoingId()).isEqualTo(outId.toString());
            assertThat(res.getFriendRequestIncomingId()).isNull();
        }

        @Test
        @DisplayName("pending request they sent → friendRequestIncomingId set, outgoing null")
        void incomingPending() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            FriendRequest in = FriendRequest.builder().sender(t).receiver(me)
                    .status(FriendRequestStatus.PENDING).build();
            UUID inId = UUID.randomUUID();
            in.setUuid(inId);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, t))
                    .thenReturn(Optional.empty());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(t, me))
                    .thenReturn(Optional.of(in));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getFriendRequestIncomingId()).isEqualTo(inId.toString());
            assertThat(res.getFriendRequestOutgoingId()).isNull();
        }

        @Test
        @DisplayName("non-PENDING request is ignored → no direction ids")
        void nonPendingIgnored() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            FriendRequest rejected = FriendRequest.builder().sender(me).receiver(t)
                    .status(FriendRequestStatus.REJECTED).build();
            rejected.setUuid(UUID.randomUUID());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(me, t))
                    .thenReturn(Optional.of(rejected));
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(t, me))
                    .thenReturn(Optional.empty());

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getFriendRequestOutgoingId()).isNull();
            assertThat(res.getFriendRequestIncomingId()).isNull();
        }

        @Test
        @DisplayName("already friends → request-direction lookups skipped, both ids null")
        void friendsSkipRequestLookup() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(friendRepository.findByUserAndFriend(me, t)).thenReturn(Optional.of(new Friend()));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isFriend()).isTrue();
            assertThat(res.getFriendRequestOutgoingId()).isNull();
            assertThat(res.getFriendRequestIncomingId()).isNull();
            verify(friendRequestRepository, never())
                    .findFirstBySenderAndReceiverOrderByIdDesc(any(), any());
        }

        @Test
        @DisplayName("either-direction block sets isBlocked=true")
        void blockedFlag() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(blockUserRepository.existsByUserAndBlocked(t, me)).thenReturn(true);

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.isBlocked()).isTrue();
        }

        @Test
        @DisplayName("friends-only target with no friendship → canMessage=false")
        void friendsOnlyBlocksMessaging() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            UserSetting s = UserSetting.builder().messagingPrivacy(MessagingPrivacy.FRIENDS_ONLY).build();
            when(userSettingRepository.findByUser(t)).thenReturn(Optional.of(s));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getMessagingFriendsOnly()).isTrue();
            assertThat(res.getCanMessage()).isFalse();
        }

        @Test
        @DisplayName("friends-only target WITH an active friendship → canMessage=true")
        void friendsOnlyAllowsFriend() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            UserSetting s = UserSetting.builder().messagingPrivacy(MessagingPrivacy.FRIENDS_ONLY).build();
            when(userSettingRepository.findByUser(t)).thenReturn(Optional.of(s));
            Friend f = new Friend();
            f.setDeleted(false);
            when(friendRepository.findByUserAndFriend(me, t)).thenReturn(Optional.of(f));

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getCanMessage()).isTrue();
        }

        @Test
        @DisplayName("presence + apparent last-seen come from PresenceService for other users")
        void presenceForOther() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(presenceService.getStatus(t)).thenReturn(PresenceStatus.IDLE);
            when(presenceService.getApparentLastSeen(t)).thenReturn(null);

            UserResponse res = service.getUserById(TARGET_UUID.toString(), me);

            assertThat(res.getPresence()).isEqualTo("idle");
            assertThat(res.getLastSeen()).isNull();
            verify(presenceService, never()).getLastSeen(t);
        }

        @Test
        @DisplayName("unknown uuid → NotFoundException TM_USER_NOT_FOUND")
        void notFound() {
            User me = viewer();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserById(TARGET_UUID.toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getPublicProfileByUsername")
    class GetPublicProfile {

        @Test
        @DisplayName("real active account → trimmed public projection with counts + presence")
        void returnsProfile() {
            User u = target();
            when(userRepository.findByUsernameIgnoreCase("neo")).thenReturn(Optional.of(u));
            when(userMapper.toUserResponse(u)).thenReturn(UserResponse.builder()
                    .id(TARGET_UUID.toString()).name("Target").username("neo")
                    .avatar("a.png").bio("hi").isVerified(true).createdAt("2020").build());
            when(presenceService.getStatus(u)).thenReturn(PresenceStatus.ONLINE);
            when(userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(u, "ACCEPTED")).thenReturn(4L);
            when(userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(u, "ACCEPTED")).thenReturn(2L);
            when(postRepository.countVisibleByUser(u)).thenReturn(9L);
            when(reputationService.getFor(TARGET_UUID.toString())).thenReturn(
                    ReputationResponse.builder().level(5).starRank("GOLD").prestigeCount(1).build());

            PublicProfileResponse res = service.getPublicProfileByUsername("  neo ");

            assertThat(res.getUsername()).isEqualTo("neo");
            assertThat(res.getPresence()).isEqualTo("online");
            assertThat(res.getFollowersCount()).isEqualTo(4L);
            assertThat(res.getFollowingCount()).isEqualTo(2L);
            assertThat(res.getPostsCount()).isEqualTo(9L);
            assertThat(res.getLevel()).isEqualTo(5);
            assertThat(res.getStarRank()).isEqualTo("GOLD");
            assertThat(res.getPrestigeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("reputation lookup failure is fail-open (level stays 0)")
        void reputationFailOpen() {
            User u = target();
            when(userRepository.findByUsernameIgnoreCase("neo")).thenReturn(Optional.of(u));
            when(userMapper.toUserResponse(u)).thenReturn(UserResponse.builder()
                    .id(TARGET_UUID.toString()).username("neo").build());
            when(presenceService.getStatus(u)).thenReturn(PresenceStatus.ONLINE);
            when(reputationService.getFor(anyString())).thenThrow(new RuntimeException("rep down"));

            PublicProfileResponse res = service.getPublicProfileByUsername("neo");

            assertThat(res.getLevel()).isEqualTo(0);
            assertThat(res.getStarRank()).isNull();
        }

        @Test
        @DisplayName("null username → NotFoundException TM_USER_NOT_FOUND")
        void nullUsername() {
            assertThatThrownBy(() -> service.getPublicProfileByUsername(null))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("blank username → NotFoundException TM_USER_NOT_FOUND")
        void blankUsername() {
            assertThatThrownBy(() -> service.getPublicProfileByUsername("   "))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("unknown username → NotFoundException TM_USER_NOT_FOUND")
        void unknownUsername() {
            when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPublicProfileByUsername("ghost"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("guest account is not publicly reachable → TM_USER_NOT_FOUND")
        void guestHidden() {
            User u = target();
            u.setGuest(true);
            when(userRepository.findByUsernameIgnoreCase("g")).thenReturn(Optional.of(u));

            assertThatThrownBy(() -> service.getPublicProfileByUsername("g"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("banned account is not publicly reachable → TM_USER_NOT_FOUND")
        void bannedHidden() {
            User u = target();
            u.setBanned(true);
            when(userRepository.findByUsernameIgnoreCase("b")).thenReturn(Optional.of(u));

            assertThatThrownBy(() -> service.getPublicProfileByUsername("b"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("soft-deleted account is not publicly reachable → TM_USER_NOT_FOUND")
        void deletedHidden() {
            User u = target();
            u.setDeleted(true);
            when(userRepository.findByUsernameIgnoreCase("d")).thenReturn(Optional.of(u));

            assertThatThrownBy(() -> service.getPublicProfileByUsername("d"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getSmartProfileCard")
    class GetSmartProfileCard {

        private void stubTarget(User t) {
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
        }

        @Test
        @DisplayName("builds the card with mutual count, compatibility and best-effort enrichments")
        void buildsCard() {
            User me = viewer();
            User t = target();
            stubTarget(t);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(userMapper.toUserResponse(t)).thenReturn(UserResponse.builder()
                    .id(TARGET_UUID.toString()).name("Target").username("user2").age(25).build());
            CompatibilityScore score = CompatibilityScore.builder().overall(72).bucket("HIGH").build();
            when(compatibilityService.score(me, t)).thenReturn(score);
            when(streakService.getStreak(t)).thenReturn(StreakResponse.builder().currentStreak(4).build());
            when(postRepository.countRecentPublicByUser(eq(t), any(Instant.class))).thenReturn(3L);

            SmartProfileCardResponse res = service.getSmartProfileCard(TARGET_UUID.toString(), me);

            assertThat(res.getId()).isEqualTo(TARGET_UUID.toString());
            assertThat(res.getName()).isEqualTo("Target");
            assertThat(res.getMutualFriendsCount()).isEqualTo(0);
            assertThat(res.getOnlineStreak()).isEqualTo(4);
            assertThat(res.getRecentPublicPosts()).isEqualTo(3);
            assertThat(res.getCompatibility()).isSameAs(score);
        }

        @Test
        @DisplayName("zero streak / zero recent posts stay null (omitted pills)")
        void zeroEnrichmentsNull() {
            User me = viewer();
            User t = target();
            stubTarget(t);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(compatibilityService.score(me, t)).thenReturn(CompatibilityScore.builder().build());
            when(streakService.getStreak(t)).thenReturn(StreakResponse.builder().currentStreak(0).build());
            when(postRepository.countRecentPublicByUser(eq(t), any(Instant.class))).thenReturn(0L);

            SmartProfileCardResponse res = service.getSmartProfileCard(TARGET_UUID.toString(), me);

            assertThat(res.getOnlineStreak()).isNull();
            assertThat(res.getRecentPublicPosts()).isNull();
        }

        @Test
        @DisplayName("streak lookup failure is swallowed (card still renders)")
        void streakFailOpen() {
            User me = viewer();
            User t = target();
            stubTarget(t);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(compatibilityService.score(me, t)).thenReturn(CompatibilityScore.builder().build());
            when(streakService.getStreak(t)).thenThrow(new RuntimeException("streak down"));
            when(postRepository.countRecentPublicByUser(eq(t), any(Instant.class))).thenReturn(0L);

            SmartProfileCardResponse res = service.getSmartProfileCard(TARGET_UUID.toString(), me);

            assertThat(res.getOnlineStreak()).isNull();
        }

        @Test
        @DisplayName("recent-posts count failure is swallowed (card still renders)")
        void recentPostsFailOpen() {
            User me = viewer();
            User t = target();
            stubTarget(t);
            when(userRepository.findById(1L)).thenReturn(Optional.of(me));
            when(compatibilityService.score(me, t)).thenReturn(CompatibilityScore.builder().build());
            when(streakService.getStreak(t)).thenReturn(StreakResponse.builder().currentStreak(0).build());
            when(postRepository.countRecentPublicByUser(eq(t), any(Instant.class)))
                    .thenThrow(new RuntimeException("post count down"));

            SmartProfileCardResponse res = service.getSmartProfileCard(TARGET_UUID.toString(), me);

            assertThat(res.getRecentPublicPosts()).isNull();
        }

        @Test
        @DisplayName("detached viewer falls back to the security principal for scoring")
        void viewerFallback() {
            User me = viewer();
            User t = target();
            stubTarget(t);
            when(userRepository.findById(1L)).thenReturn(Optional.empty()); // viewer detached
            when(compatibilityService.score(me, t)).thenReturn(CompatibilityScore.builder().build());
            when(streakService.getStreak(t)).thenReturn(StreakResponse.builder().currentStreak(0).build());
            when(postRepository.countRecentPublicByUser(eq(t), any(Instant.class))).thenReturn(0L);

            service.getSmartProfileCard(TARGET_UUID.toString(), me);

            verify(compatibilityService).score(me, t);
        }

        @Test
        @DisplayName("unknown target → NotFoundException TM_024")
        void notFound() {
            User me = viewer();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSmartProfileCard(TARGET_UUID.toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_024"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("searchUsers")
    class SearchUsers {

        @Test
        @DisplayName("valid query, first page, more results → cursor advances")
        void firstPageHasNext() {
            User me = viewer();
            User a = target();
            Page<User> page = new PageImpl<>(List.of(a), PageRequest.of(0, 1), 5L);
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PaginatedResponse<UserResponse> res = service.searchUsers("ab", 1, null, me);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getPagination().isHasNext()).isTrue();
            assertThat(res.getPagination().getCursor()).isEqualTo("1");
            assertThat(res.getPagination().getTotal()).isEqualTo(5L);
        }

        @Test
        @DisplayName("last page → cursor is null and hasNext=false")
        void lastPageNoCursor() {
            User me = viewer();
            Page<User> page = new PageImpl<>(List.of(target()), PageRequest.of(0, 10), 1L);
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PaginatedResponse<UserResponse> res = service.searchUsers("ab", 10, null, me);

            assertThat(res.getPagination().isHasNext()).isFalse();
            assertThat(res.getPagination().getCursor()).isNull();
        }

        @Test
        @DisplayName("numeric cursor selects that page")
        void cursorPaging() {
            User me = viewer();
            Page<User> page = new PageImpl<>(List.of(), PageRequest.of(2, 5), 12L);
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PaginatedResponse<UserResponse> res = service.searchUsers("ab", 5, "2", me);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("non-numeric cursor is ignored → page 0")
        void invalidCursorIgnored() {
            User me = viewer();
            Page<User> page = new PageImpl<>(List.of(), PageRequest.of(0, 5), 0L);
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            service.searchUsers("ab", 5, "not-a-number", me);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty page → empty items, no NPE")
        void emptyResult() {
            User me = viewer();
            Page<User> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0L);
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PaginatedResponse<UserResponse> res = service.searchUsers("ab", 10, null, me);

            assertThat(res.getItems()).isEmpty();
        }

        @Test
        @DisplayName("null query → BadRequestException TM_070")
        void nullQuery() {
            User me = viewer();
            assertThatThrownBy(() -> service.searchUsers(null, 10, null, me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_070"));
        }

        @Test
        @DisplayName("query shorter than 2 chars (after trim) → BadRequestException TM_070")
        void shortQuery() {
            User me = viewer();
            assertThatThrownBy(() -> service.searchUsers(" a ", 10, null, me))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_070"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getBlockedUsers")
    class GetBlockedUsers {

        @Test
        @DisplayName("maps each block record to its DTO")
        void mapsBlocks() {
            User me = viewer();
            User blocked = target();
            blocked.setProfileImage("b.png");
            BlockUser bu = BlockUser.builder().user(me).blocked(blocked).build();
            bu.setCreatedAt(NOW);
            when(blockUserRepository.findByUser(me)).thenReturn(List.of(bu));

            PaginatedResponse<BlockedUserResponse> res = service.getBlockedUsers(me);

            assertThat(res.getItems()).hasSize(1);
            BlockedUserResponse item = res.getItems().get(0);
            assertThat(item.getId()).isEqualTo(TARGET_UUID.toString());
            assertThat(item.getName()).isEqualTo("Target");
            assertThat(item.getAvatar()).isEqualTo("b.png");
            assertThat(item.getBlockedAt()).isEqualTo(NOW.toString());
            assertThat(res.getPagination().getTotal()).isEqualTo(1L);
        }

        @Test
        @DisplayName("null createdAt falls back to now (no NPE)")
        void nullCreatedAt() {
            User me = viewer();
            BlockUser bu = BlockUser.builder().user(me).blocked(target()).build();
            when(blockUserRepository.findByUser(me)).thenReturn(List.of(bu));

            PaginatedResponse<BlockedUserResponse> res = service.getBlockedUsers(me);

            assertThat(res.getItems().get(0).getBlockedAt()).isNotNull();
        }

        @Test
        @DisplayName("no blocks → empty list")
        void empty() {
            User me = viewer();
            when(blockUserRepository.findByUser(me)).thenReturn(List.of());

            PaginatedResponse<BlockedUserResponse> res = service.getBlockedUsers(me);

            assertThat(res.getItems()).isEmpty();
            assertThat(res.getPagination().getTotal()).isEqualTo(0L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reportUser")
    class ReportUser {

        @Test
        @DisplayName("first report is persisted with the given reason/details")
        void savesReport() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(matchReportRepository.existsByReporterIdAndReportedIdAndStatus(1L, 2L, "PENDING"))
                    .thenReturn(false);

            service.reportUser(TARGET_UUID.toString(), "spam", "sent links", me);

            ArgumentCaptor<MatchReport> saved = ArgumentCaptor.forClass(MatchReport.class);
            verify(matchReportRepository).save(saved.capture());
            MatchReport r = saved.getValue();
            assertThat(r.getReporter()).isSameAs(me);
            assertThat(r.getReported()).isSameAs(t);
            assertThat(r.getReason()).isEqualTo("spam");
            assertThat(r.getDetails()).isEqualTo("sent links");
        }

        @Test
        @DisplayName("null reason defaults to \"other\"")
        void defaultReason() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(matchReportRepository.existsByReporterIdAndReportedIdAndStatus(1L, 2L, "PENDING"))
                    .thenReturn(false);

            service.reportUser(TARGET_UUID.toString(), null, null, me);

            ArgumentCaptor<MatchReport> saved = ArgumentCaptor.forClass(MatchReport.class);
            verify(matchReportRepository).save(saved.capture());
            assertThat(saved.getValue().getReason()).isEqualTo("other");
        }

        @Test
        @DisplayName("unknown target → NotFoundException TM_USER_NOT_FOUND")
        void notFound() {
            User me = viewer();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reportUser(TARGET_UUID.toString(), "spam", null, me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
            verify(matchReportRepository, never()).save(any());
        }

        @Test
        @DisplayName("existing PENDING report → ConflictException TM_182, nothing saved")
        void duplicatePending() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(matchReportRepository.existsByReporterIdAndReportedIdAndStatus(1L, 2L, "PENDING"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.reportUser(TARGET_UUID.toString(), "spam", null, me))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_182"));
            verify(matchReportRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getMutualFriends")
    class GetMutualFriends {

        @Test
        @DisplayName("returns the intersection of the two friend graphs")
        void intersection() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            User a = user(10L, UUID.randomUUID(), "A");
            User b = user(11L, UUID.randomUUID(), "B");
            User c = user(12L, UUID.randomUUID(), "C");
            when(friendRepository.findFriendsByUser(me)).thenReturn(List.of(a, b));
            when(friendRepository.findFriendsByUser(t)).thenReturn(List.of(b, c));

            MutualFriendsResponse res = service.getMutualFriends(TARGET_UUID.toString(), me);

            assertThat(res.getCount()).isEqualTo(1);
            assertThat(res.getUsers()).hasSize(1);
        }

        @Test
        @DisplayName("no overlap → count 0, empty users")
        void noOverlap() {
            User me = viewer();
            User t = target();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(t));
            when(friendRepository.findFriendsByUser(me))
                    .thenReturn(List.of(user(10L, UUID.randomUUID(), "A")));
            when(friendRepository.findFriendsByUser(t))
                    .thenReturn(List.of(user(11L, UUID.randomUUID(), "B")));

            MutualFriendsResponse res = service.getMutualFriends(TARGET_UUID.toString(), me);

            assertThat(res.getCount()).isEqualTo(0);
            assertThat(res.getUsers()).isEmpty();
        }

        @Test
        @DisplayName("unknown target → NotFoundException TM_USER_NOT_FOUND")
        void notFound() {
            User me = viewer();
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMutualFriends(TARGET_UUID.toString(), me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getLobbyUsers")
    class GetLobbyUsers {

        @Test
        @DisplayName("resolves the redis lobby set and maps each user")
        void mapsLobby() {
            User me = viewer();
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members("lobby:users")).thenReturn(Set.of("user2"));
            User t = target();
            when(userRepository.findAllByUsernameInExcludeSelf(Set.of("user2"), 1L))
                    .thenReturn(List.of(t));

            List<UserResponse> res = service.getLobbyUsers(me);

            assertThat(res).hasSize(1);
        }

        @Test
        @DisplayName("null lobby set → empty list, no user lookup")
        void nullMembers() {
            User me = viewer();
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members("lobby:users")).thenReturn(null);

            List<UserResponse> res = service.getLobbyUsers(me);

            assertThat(res).isEmpty();
            verify(userRepository, never()).findAllByUsernameInExcludeSelf(any(), any());
        }

        @Test
        @DisplayName("empty lobby set → empty list")
        void emptyMembers() {
            User me = viewer();
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members("lobby:users")).thenReturn(Set.of());

            assertThat(service.getLobbyUsers(me)).isEmpty();
        }

        @Test
        @DisplayName("null currentUser → passes null self id to the exclude-self query")
        void nullCurrentUser() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members("lobby:users")).thenReturn(Set.of("user2"));
            when(userRepository.findAllByUsernameInExcludeSelf(Set.of("user2"), null))
                    .thenReturn(List.of());

            List<UserResponse> res = service.getLobbyUsers(null);

            assertThat(res).isEmpty();
            verify(userRepository).findAllByUsernameInExcludeSelf(Set.of("user2"), null);
        }
    }
}
