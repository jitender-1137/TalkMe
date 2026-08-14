package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserFollow;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.repository.UserFollowRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.FollowService;
import com.neo.chat.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Directional follow graph. Follows are soft-deleted (an {@code isDeleted} flag) and currently
 * auto-ACCEPTED; a new follow fires a "New follower" {@link NotificationService} notification.
 * Follower/following queries and counts filter to ACCEPTED, non-deleted edges.
 */
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    /**
     * Resolve a user by uuid string.
     *
     * @throws com.neo.chat.exception.NotFoundException if no such user exists
     */
    private User getUser(String uuid) {
        return userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_100"));
    }

    /**
     * Follow another user (auto-ACCEPTED) and notify the target of the new follower.
     *
     * @param targetUserUuid uuid of the user to follow
     * @param currentUser    the follower
     * @throws com.neo.chat.exception.NotFoundException   if the target user is missing
     * @throws com.neo.chat.exception.BadRequestException if following self or already following
     */
    @Override
    @Transactional
    public void followUser(String targetUserUuid, User currentUser) {
        User targetUser = getUser(targetUserUuid);
        if (targetUser.getId().equals(currentUser.getId())) {
            throw new BadRequestException("You cannot follow yourself", "TM_250");
        }

        Optional<UserFollow> existing = userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser);
        if (existing.isPresent()) {
            throw new BadRequestException("You are already following this user", "TM_251");
        }

        UserFollow follow = UserFollow.builder()
                .follower(currentUser)
                .following(targetUser)
                .status("ACCEPTED") // Will update for private accounts later
                .build();

        userFollowRepository.save(follow);

        notificationService.createNotification(
                targetUser,
                "New follower",
                currentUser.getName() + " started following you.",
                "FOLLOW",
                currentUser.getUuid().toString(),
                currentUser,
                null
        );
    }

    /**
     * Stop following a user by soft-deleting the follow edge.
     *
     * @param targetUserUuid uuid of the user to unfollow
     * @param currentUser    the follower
     * @throws com.neo.chat.exception.NotFoundException   if the target user is missing
     * @throws com.neo.chat.exception.BadRequestException if not currently following the target
     */
    @Override
    @Transactional
    public void unfollowUser(String targetUserUuid, User currentUser) {
        User targetUser = getUser(targetUserUuid);
        UserFollow follow = userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser)
                .orElseThrow(() -> new BadRequestException("You are not following this user", "TM_252"));

        follow.setDeleted(true);
        userFollowRepository.save(follow);
    }

    /**
     * Remove one of the current user's followers by soft-deleting their follow edge.
     *
     * @param followerUuid uuid of the follower to remove
     * @param currentUser  the user being followed
     * @throws com.neo.chat.exception.NotFoundException   if the follower user is missing
     * @throws com.neo.chat.exception.BadRequestException if that user is not following currentUser
     */
    @Override
    @Transactional
    public void removeFollower(String followerUuid, User currentUser) {
        User follower = getUser(followerUuid);
        UserFollow follow = userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(follower, currentUser)
                .orElseThrow(() -> new BadRequestException("This user is not following you", "TM_253"));

        follow.setDeleted(true);
        userFollowRepository.save(follow);
    }

    /**
     * Page through a user's accepted, non-deleted followers.
     *
     * @param userUuid uuid of the user whose followers to list
     * @param pageable paging/sorting
     * @return a page of follower users as response DTOs
     * @throws com.neo.chat.exception.NotFoundException if the user is missing
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuthUserResponse> getFollowers(String userUuid, Pageable pageable) {
        User user = getUser(userUuid);
        return userFollowRepository.findByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED", pageable)
                .map(f -> userMapper.toAuthUserResponse(f.getFollower()));
    }

    /**
     * Page through the users a given user follows (accepted, non-deleted).
     *
     * @param userUuid uuid of the user whose following list to fetch
     * @param pageable paging/sorting
     * @return a page of followed users as response DTOs
     * @throws com.neo.chat.exception.NotFoundException if the user is missing
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuthUserResponse> getFollowing(String userUuid, Pageable pageable) {
        User user = getUser(userUuid);
        return userFollowRepository.findByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED", pageable)
                .map(f -> userMapper.toAuthUserResponse(f.getFollowing()));
    }

    /**
     * Count a user's accepted, non-deleted followers.
     *
     * @param userUuid uuid of the user
     * @return the follower count
     * @throws com.neo.chat.exception.NotFoundException if the user is missing
     */
    @Override
    @Transactional(readOnly = true)
    public long getFollowersCount(String userUuid) {
        User user = getUser(userUuid);
        return userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED");
    }

    /**
     * Count how many users a given user follows (accepted, non-deleted).
     *
     * @param userUuid uuid of the user
     * @return the following count
     * @throws com.neo.chat.exception.NotFoundException if the user is missing
     */
    @Override
    @Transactional(readOnly = true)
    public long getFollowingCount(String userUuid) {
        User user = getUser(userUuid);
        return userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED");
    }

    /**
     * Whether {@code follower} has an accepted, non-deleted follow edge to {@code following}.
     *
     * @param follower  the potential follower
     * @param following the potentially followed user
     * @return true if an accepted follow edge exists
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(User follower, User following) {
        return userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(follower, following, "ACCEPTED");
    }
}
