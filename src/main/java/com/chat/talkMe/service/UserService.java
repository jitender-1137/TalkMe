package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.UserResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface UserService {
    UserResponse getCurrentUser(User currentUser);
    UserResponse updateProfile(UpdateProfileRequest request, User currentUser);
    Map<String, String> uploadAvatar(MultipartFile file, User currentUser);
    void removeAvatar(User currentUser);
    UserResponse getUserById(String userId, User currentUser);
    PaginatedResponse<UserResponse> searchUsers(String query, int limit, String cursor, User currentUser);
    PaginatedResponse<BlockedUserResponse> getBlockedUsers(User currentUser);
    void reportUser(String userId, String reason, String description, User currentUser);
    MutualFriendsResponse getMutualFriends(String userId, User currentUser);
    java.util.List<UserResponse> getLobbyUsers(User currentUser);
}
