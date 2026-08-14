package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AuthUserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Directional follow graph: follow/unfollow, remove followers, and follower/following listings and counts.
 */
public interface FollowService {
    void followUser(String targetUserUuid, User currentUser);

    void unfollowUser(String targetUserUuid, User currentUser);

    void removeFollower(String followerUuid, User currentUser);

    Page<AuthUserResponse> getFollowers(String userUuid, Pageable pageable);

    Page<AuthUserResponse> getFollowing(String userUuid, Pageable pageable);

    long getFollowersCount(String userUuid);

    long getFollowingCount(String userUuid);

    boolean isFollowing(User follower, User following);
}
