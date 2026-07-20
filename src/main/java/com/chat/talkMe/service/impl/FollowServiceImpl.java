package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserFollow;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.FollowService;
import com.chat.talkMe.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    private User getUser(String uuid) {
        return userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_100"));
    }

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

    @Override
    @Transactional
    public void unfollowUser(String targetUserUuid, User currentUser) {
        User targetUser = getUser(targetUserUuid);
        UserFollow follow = userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(currentUser, targetUser)
                .orElseThrow(() -> new BadRequestException("You are not following this user", "TM_252"));
        
        follow.setDeleted(true);
        userFollowRepository.save(follow);
    }

    @Override
    @Transactional
    public void removeFollower(String followerUuid, User currentUser) {
        User follower = getUser(followerUuid);
        UserFollow follow = userFollowRepository.findByFollowerAndFollowingAndIsDeletedFalse(follower, currentUser)
                .orElseThrow(() -> new BadRequestException("This user is not following you", "TM_253"));
        
        follow.setDeleted(true);
        userFollowRepository.save(follow);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuthUserResponse> getFollowers(String userUuid, Pageable pageable) {
        User user = getUser(userUuid);
        return userFollowRepository.findByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED", pageable)
                .map(f -> userMapper.toAuthUserResponse(f.getFollower()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuthUserResponse> getFollowing(String userUuid, Pageable pageable) {
        User user = getUser(userUuid);
        return userFollowRepository.findByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED", pageable)
                .map(f -> userMapper.toAuthUserResponse(f.getFollowing()));
    }

    @Override
    @Transactional(readOnly = true)
    public long getFollowersCount(String userUuid) {
        User user = getUser(userUuid);
        return userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED");
    }

    @Override
    @Transactional(readOnly = true)
    public long getFollowingCount(String userUuid) {
        User user = getUser(userUuid);
        return userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(User follower, User following) {
        return userFollowRepository.existsByFollowerAndFollowingAndStatusAndIsDeletedFalse(follower, following, "ACCEPTED");
    }
}
