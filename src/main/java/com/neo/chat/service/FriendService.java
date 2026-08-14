package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.FriendRequestResponse;

import java.util.List;

/**
 * Mutual friendships and friend requests, plus user blocking/unblocking.
 */
public interface FriendService {
    FriendRequestResponse sendFriendRequest(String receiverUuid, User currentUser);

    void acceptFriendRequest(String requestUuid, User currentUser);

    void rejectFriendRequest(String requestUuid, User currentUser);

    void cancelFriendRequest(String requestUuid, User currentUser);

    List<AuthUserResponse> getFriends(User currentUser);

    List<FriendRequestResponse> getFriendRequests(User currentUser);

    void removeFriend(String friendUuid, User currentUser);

    void blockUser(String userUuid, User currentUser);

    void unblockUser(String userUuid, User currentUser);
}
