package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.PublicProfileResponse;
import com.chat.talkMe.dto.response.SmartProfileCardResponse;
import com.chat.talkMe.dto.response.UserResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * User profile, identity and discovery operations (fetch/update profile, username, avatar,
 * search, blocked list, reporting, mutual friends, public profile and smart-card views).
 */
public interface UserService {
    UserResponse getCurrentUser(User currentUser);

    UserResponse updateProfile(UpdateProfileRequest request, User currentUser);

    /**
     * Change the current user's username (unique; soft-deleted names stay reserved).
     */
    UserResponse changeUsername(String newUsername, User currentUser);

    /**
     * True when `username` is free for the current user to take (case-insensitive).
     */
    boolean isUsernameAvailable(String username, User currentUser);

    /**
     * Fast, dedicated update for the hot "change my mood" action (feature #4).
     */
    UserResponse updateMood(String moodValue, User currentUser);

    Map<String, String> uploadAvatar(MultipartFile file, User currentUser);

    void removeAvatar(User currentUser);

    UserResponse getUserById(String userId, User currentUser);

    /**
     * Trimmed, UNAUTHENTICATED profile lookup by username for the shareable {@code /@username}
     * link. Returns only public fields (never PII); 404s for missing/guest/banned/deleted users.
     */
    PublicProfileResponse getPublicProfileByUsername(String username);

    /**
     * At-a-glance "smart card" (feature #20) with late-night attributes + compatibility.
     */
    SmartProfileCardResponse getSmartProfileCard(String userId, User currentUser);

    PaginatedResponse<UserResponse> searchUsers(String query, int limit, String cursor, User currentUser);

    PaginatedResponse<BlockedUserResponse> getBlockedUsers(User currentUser);

    void reportUser(String userId, String reason, String description, User currentUser);

    MutualFriendsResponse getMutualFriends(String userId, User currentUser);

    List<UserResponse> getLobbyUsers(User currentUser);
}
