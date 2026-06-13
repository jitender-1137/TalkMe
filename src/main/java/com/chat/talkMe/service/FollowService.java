package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
