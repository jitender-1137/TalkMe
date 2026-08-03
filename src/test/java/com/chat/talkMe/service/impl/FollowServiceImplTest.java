package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserFollow;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FollowServiceImpl} — the follow graph service.
 *
 * <p>Every public method resolves the target user through the private {@code getUser}
 * helper first (missing → {@link NotFoundException} {@code TM_100}). The mutating methods
 * add their own guards: self-follow ({@code TM_250}), duplicate follow ({@code TM_251}),
 * not-following on unfollow ({@code TM_252}) and not-a-follower on remove ({@code TM_253}).
 * Side effects asserted: the persisted {@link UserFollow}, the soft-delete flag, and the
 * follow notification fan-out.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowServiceImpl (unit)")
class FollowServiceImplTest {

    private static final String TARGET_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String CURRENT_UUID = "22222222-2222-2222-2222-222222222222";

    @Mock private UserFollowRepository userFollowRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @Mock private NotificationService notificationService;

    private FollowServiceImpl service;

    private User currentUser;
    private User targetUser;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl(userFollowRepository, userRepository, userMapper, notificationService);

        currentUser = User.builder().username("alice").name("Alice").build();
        currentUser.setId(1L);
        currentUser.setUuid(UUID.fromString(CURRENT_UUID));

        targetUser = User.builder().username("bob").name("Bob").build();
        targetUser.setId(2L);
        targetUser.setUuid(UUID.fromString(TARGET_UUID));
    }

    @Nested
    @DisplayName("followUser")
    class FollowUser {

        @Test
        @DisplayName("happy path → saves an ACCEPTED follow and fires a FOLLOW notification")
        void followsAndNotifies() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser))
                    .thenReturn(Optional.empty());

            service.followUser(TARGET_UUID, currentUser);

            ArgumentCaptor<UserFollow> saved = ArgumentCaptor.forClass(UserFollow.class);
            verify(userFollowRepository).save(saved.capture());
            assertThat(saved.getValue().getFollower()).isSameAs(currentUser);
            assertThat(saved.getValue().getFollowing()).isSameAs(targetUser);
            assertThat(saved.getValue().getStatus()).isEqualTo("ACCEPTED");

            verify(notificationService).createNotification(
                    eq(targetUser),
                    eq("New follower"),
                    eq("Alice started following you."),
                    eq("FOLLOW"),
                    eq(CURRENT_UUID),
                    eq(currentUser),
                    isNull());
        }

        @Test
        @DisplayName("target user not found → NotFoundException TM_100, nothing saved")
        void throwsWhenTargetMissing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.followUser(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));

            verify(userFollowRepository, never()).save(any());
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("following yourself → BadRequestException TM_250, nothing saved")
        void throwsOnSelfFollow() {
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.of(currentUser));

            assertThatThrownBy(() -> service.followUser(CURRENT_UUID, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_250"));

            verify(userFollowRepository, never()).save(any());
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("already following → BadRequestException TM_251, nothing saved")
        void throwsWhenAlreadyFollowing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser))
                    .thenReturn(Optional.of(UserFollow.builder().follower(currentUser).following(targetUser).build()));

            assertThatThrownBy(() -> service.followUser(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_251"));

            verify(userFollowRepository, never()).save(any());
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("malformed target uuid → IllegalArgumentException, no repository lookup")
        void throwsOnMalformedUuid() {
            assertThatThrownBy(() -> service.followUser("not-a-uuid", currentUser))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(userFollowRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("unfollowUser")
    class UnfollowUser {

        @Test
        @DisplayName("happy path → soft-deletes the follow row")
        void softDeletesFollow() {
            UserFollow follow = UserFollow.builder().follower(currentUser).following(targetUser).build();
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser))
                    .thenReturn(Optional.of(follow));

            service.unfollowUser(TARGET_UUID, currentUser);

            ArgumentCaptor<UserFollow> saved = ArgumentCaptor.forClass(UserFollow.class);
            verify(userFollowRepository).save(saved.capture());
            assertThat(saved.getValue().isDeleted()).isTrue();
        }

        @Test
        @DisplayName("target user not found → NotFoundException TM_100")
        void throwsWhenTargetMissing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unfollowUser(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));

            verify(userFollowRepository, never()).save(any());
        }

        @Test
        @DisplayName("not currently following → BadRequestException TM_252")
        void throwsWhenNotFollowing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unfollowUser(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_252"));

            verify(userFollowRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("removeFollower")
    class RemoveFollower {

        @Test
        @DisplayName("happy path → soft-deletes the follower's row")
        void softDeletesFollower() {
            UserFollow follow = UserFollow.builder().follower(targetUser).following(currentUser).build();
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(targetUser, currentUser))
                    .thenReturn(Optional.of(follow));

            service.removeFollower(TARGET_UUID, currentUser);

            ArgumentCaptor<UserFollow> saved = ArgumentCaptor.forClass(UserFollow.class);
            verify(userFollowRepository).save(saved.capture());
            assertThat(saved.getValue().isDeleted()).isTrue();
        }

        @Test
        @DisplayName("follower not found → NotFoundException TM_100")
        void throwsWhenFollowerMissing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeFollower(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));

            verify(userFollowRepository, never()).save(any());
        }

        @Test
        @DisplayName("that user is not following you → BadRequestException TM_253")
        void throwsWhenNotAFollower() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(targetUser, currentUser))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeFollower(TARGET_UUID, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_253"));

            verify(userFollowRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getFollowers")
    class GetFollowers {

        @Test
        @DisplayName("maps each accepted follow's follower to an AuthUserResponse")
        void mapsFollowers() {
            Pageable pageable = PageRequest.of(0, 20);
            UserFollow follow = UserFollow.builder().follower(currentUser).following(targetUser).build();
            AuthUserResponse dto = new AuthUserResponse();
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowingAndStatusAndIsDeletedFalse(targetUser, "ACCEPTED", pageable))
                    .thenReturn(new PageImpl<>(List.of(follow)));
            when(userMapper.toAuthUserResponse(currentUser)).thenReturn(dto);

            Page<AuthUserResponse> result = service.getFollowers(TARGET_UUID, pageable);

            assertThat(result.getContent()).containsExactly(dto);
        }

        @Test
        @DisplayName("empty page → empty result, no NPE")
        void emptyPage() {
            Pageable pageable = PageRequest.of(0, 20);
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.findByFollowingAndStatusAndIsDeletedFalse(targetUser, "ACCEPTED", pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThat(service.getFollowers(TARGET_UUID, pageable).getContent()).isEmpty();
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_100")
        void throwsWhenUserMissing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFollowers(TARGET_UUID, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));
        }
    }

    @Nested
    @DisplayName("getFollowing")
    class GetFollowing {

        @Test
        @DisplayName("maps each accepted follow's following to an AuthUserResponse")
        void mapsFollowing() {
            Pageable pageable = PageRequest.of(0, 20);
            UserFollow follow = UserFollow.builder().follower(currentUser).following(targetUser).build();
            AuthUserResponse dto = new AuthUserResponse();
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.of(currentUser));
            when(userFollowRepository.findByFollowerAndStatusAndIsDeletedFalse(currentUser, "ACCEPTED", pageable))
                    .thenReturn(new PageImpl<>(List.of(follow)));
            when(userMapper.toAuthUserResponse(targetUser)).thenReturn(dto);

            Page<AuthUserResponse> result = service.getFollowing(CURRENT_UUID, pageable);

            assertThat(result.getContent()).containsExactly(dto);
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_100")
        void throwsWhenUserMissing() {
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFollowing(CURRENT_UUID, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));
        }
    }

    @Nested
    @DisplayName("getFollowersCount")
    class GetFollowersCount {

        @Test
        @DisplayName("returns the accepted-follower count from the repository")
        void returnsCount() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.of(targetUser));
            when(userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(targetUser, "ACCEPTED")).thenReturn(7L);

            assertThat(service.getFollowersCount(TARGET_UUID)).isEqualTo(7L);
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_100")
        void throwsWhenUserMissing() {
            when(userRepository.findByUuid(UUID.fromString(TARGET_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFollowersCount(TARGET_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));
        }
    }

    @Nested
    @DisplayName("getFollowingCount")
    class GetFollowingCount {

        @Test
        @DisplayName("returns the accepted-following count from the repository")
        void returnsCount() {
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.of(currentUser));
            when(userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(currentUser, "ACCEPTED")).thenReturn(3L);

            assertThat(service.getFollowingCount(CURRENT_UUID)).isEqualTo(3L);
        }

        @Test
        @DisplayName("user not found → NotFoundException TM_100")
        void throwsWhenUserMissing() {
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFollowingCount(CURRENT_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_100"));
        }
    }

    @Nested
    @DisplayName("isFollowing")
    class IsFollowing {

        @Test
        @DisplayName("delegates to the repository existence check → true")
        void returnsTrue() {
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(
                    currentUser, targetUser, "ACCEPTED")).thenReturn(true);

            assertThat(service.isFollowing(currentUser, targetUser)).isTrue();
        }

        @Test
        @DisplayName("delegates to the repository existence check → false")
        void returnsFalse() {
            when(userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(
                    currentUser, targetUser, "ACCEPTED")).thenReturn(false);

            assertThat(service.isFollowing(currentUser, targetUser)).isFalse();
        }
    }
}
